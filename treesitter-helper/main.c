/*
 * smartdiff-treesitter: the Tree-sitter helper process of ADR 0016 (engines/05-tree-sitter-details §2, §8).
 *
 * It reads requests from standard input and writes answers to standard output until its input closes. It opens no file
 * and no network connection (engines/02 R13). All integers are little-endian.
 *
 * Request: one byte, then a u32 byte length, then that many bytes.
 *   - byte = language (0 Swift, 1 C#, 2 C, 3 C++): the bytes are the text as UTF-16LE. The answer is a u32 node count,
 *     then six i32 per node in pre-order: symbol, start, end (in UTF-16 units), parent index (-1 for the root), field id
 *     (0 for none) and flags (1 named, 2 error, 4 missing).
 *   - byte = 0x80 | language, length 0: the answer is the language's names, a u32 symbol count, each symbol name as a
 *     u16 byte length and UTF-8 bytes, then a u32 field count and the field names the same way (field id 0 has none).
 *   - any other byte: the helper exits with status 2.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif
#include "tree_sitter/api.h"

const TSLanguage *tree_sitter_swift(void);
const TSLanguage *tree_sitter_c_sharp(void);
const TSLanguage *tree_sitter_c(void);
const TSLanguage *tree_sitter_cpp(void);

#define LANGUAGES 4
#define METADATA 0x80

static void *must(void *p) {
    if (!p) exit(3);
    return p;
}

static int read_all(void *p, size_t n) { return fread(p, 1, n, stdin) == n; }

static void write_u32(uint32_t v) {
    uint8_t b[4] = { (uint8_t)v, (uint8_t)(v >> 8), (uint8_t)(v >> 16), (uint8_t)(v >> 24) };
    fwrite(b, 1, 4, stdout);
}

static void write_name(const char *name) {
    size_t length = name ? strlen(name) : 0;
    if (length > 0xFFFF) length = 0xFFFF;
    uint8_t b[2] = { (uint8_t)length, (uint8_t)(length >> 8) };
    fwrite(b, 1, 2, stdout);
    if (length) fwrite(name, 1, length, stdout);
}

static void metadata(const TSLanguage *language) {
    uint32_t symbols = ts_language_symbol_count(language);
    write_u32(symbols);
    for (uint32_t s = 0; s < symbols; s++) write_name(ts_language_symbol_name(language, (TSSymbol)s));
    uint32_t fields = ts_language_field_count(language);
    write_u32(fields + 1);
    for (uint32_t f = 0; f <= fields; f++) write_name(f == 0 ? "" : ts_language_field_name_for_id(language, (TSFieldId)f));
}

/* A growable array of i32. */
typedef struct { int32_t *data; size_t length, capacity; } Ints;

static void push(Ints *ints, int32_t value) {
    if (ints->length == ints->capacity) {
        ints->capacity = ints->capacity ? ints->capacity * 2 : 1 << 16;
        ints->data = must(realloc(ints->data, ints->capacity * sizeof(int32_t)));
    }
    ints->data[ints->length++] = value;
}

static void parse(TSParser *parser, const TSLanguage *language, const char *text, uint32_t length, Ints *nodes, Ints *parents) {
    ts_parser_set_language(parser, language);
    TSTree *tree = ts_parser_parse_string_encoding(parser, NULL, text, length, TSInputEncodingUTF16LE);
    if (!tree) exit(4);
    nodes->length = 0;
    parents->length = 0;
    TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));
    int32_t index = 0;
    for (;;) {
        TSNode node = ts_tree_cursor_current_node(&cursor);
        int32_t flags = (ts_node_is_named(node) ? 1 : 0) | (ts_node_is_error(node) ? 2 : 0) | (ts_node_is_missing(node) ? 4 : 0);
        push(nodes, ts_node_symbol(node));
        push(nodes, (int32_t)(ts_node_start_byte(node) / 2));
        push(nodes, (int32_t)(ts_node_end_byte(node) / 2));
        push(nodes, parents->length ? parents->data[parents->length - 1] : -1);
        push(nodes, ts_tree_cursor_current_field_id(&cursor));
        push(nodes, flags);
        if (ts_tree_cursor_goto_first_child(&cursor)) {
            push(parents, index++);
            continue;
        }
        index++;
        while (!ts_tree_cursor_goto_next_sibling(&cursor)) {
            if (!ts_tree_cursor_goto_parent(&cursor)) goto done;
            parents->length--;
        }
    }
done:
    ts_tree_cursor_delete(&cursor);
    ts_tree_delete(tree);
}

int main(void) {
#ifdef _WIN32
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
#endif
    const TSLanguage *languages[LANGUAGES] = { tree_sitter_swift(), tree_sitter_c_sharp(), tree_sitter_c(), tree_sitter_cpp() };
    TSParser *parser = must(ts_parser_new());
    Ints nodes = { 0 }, parents = { 0 };
    for (;;) {
        uint8_t kind, l[4];
        if (!read_all(&kind, 1)) return 0;
        if (!read_all(l, 4)) return 1;
        uint32_t length = (uint32_t)l[0] | (uint32_t)l[1] << 8 | (uint32_t)l[2] << 16 | (uint32_t)l[3] << 24;
        uint8_t language = kind & ~METADATA;
        if (language >= LANGUAGES || (length & 1)) return 2;
        if (kind & METADATA) {
            if (length != 0) return 2;
            metadata(languages[language]);
        } else {
            char *text = must(malloc(length ? length : 1));
            if (!read_all(text, length)) return 1;
            parse(parser, languages[language], text, length, &nodes, &parents);
            free(text);
            write_u32((uint32_t)(nodes.length / 6));
            for (size_t i = 0; i < nodes.length; i++) write_u32((uint32_t)nodes.data[i]);
        }
        fflush(stdout);
    }
}
