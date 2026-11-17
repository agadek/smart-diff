/*
 * The Tree-sitter helper of ADR 0016, as spike S12 measured it (engines/05-tree-sitter-details §2). It is not built yet:
 * T1 adds the build for the six platforms of R22, the symbol and field name table, and the engine that talks to it.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "tree_sitter/api.h"
const TSLanguage *tree_sitter_swift(void); const TSLanguage *tree_sitter_c_sharp(void);
const TSLanguage *tree_sitter_c(void); const TSLanguage *tree_sitter_cpp(void);
/* Protocol: request = u8 lang, u32 byteLen, UTF-16LE text. Response = u32 nodeCount, then 6 x i32 per node. Loops until EOF. */
static int rd(void *p, size_t n) { return fread(p, 1, n, stdin) == n; }
int main(void) {
    const TSLanguage *langs[4] = { tree_sitter_swift(), tree_sitter_c_sharp(), tree_sitter_c(), tree_sitter_cpp() };
    TSParser *parser = ts_parser_new();
    int32_t *out = NULL; size_t cap = 0;
    for (;;) {
        uint8_t lang; uint32_t len;
        if (!rd(&lang, 1) || !rd(&len, 4)) return 0;
        char *text = malloc(len ? len : 1); if (!rd(text, len)) return 1;
        ts_parser_set_language(parser, langs[lang]);
        TSTree *tree = ts_parser_parse_string_encoding(parser, NULL, text, len, TSInputEncodingUTF16LE);
        size_t n = 0; int32_t stack[8192]; int depth = 0; int32_t index = 0;
        TSTreeCursor c = ts_tree_cursor_new(ts_tree_root_node(tree));
        for (;;) {
            TSNode node = ts_tree_cursor_current_node(&c);
            if (n + 6 > cap) { cap = cap ? cap * 2 : 1 << 16; out = realloc(out, cap * 4); }
            out[n++] = ts_node_symbol(node); out[n++] = ts_node_start_byte(node) / 2; out[n++] = ts_node_end_byte(node) / 2;
            out[n++] = depth ? stack[depth - 1] : -1; out[n++] = ts_tree_cursor_current_field_id(&c);
            out[n++] = (ts_node_is_named(node) ? 1 : 0) | (ts_node_is_error(node) ? 2 : 0) | (ts_node_is_missing(node) ? 4 : 0);
            if (ts_tree_cursor_goto_first_child(&c)) { stack[depth++] = index++; continue; }
            index++;
            while (!ts_tree_cursor_goto_next_sibling(&c)) { if (!ts_tree_cursor_goto_parent(&c)) goto done; depth--; }
        }
    done:
        ts_tree_cursor_delete(&c); ts_tree_delete(tree); free(text);
        uint32_t count = n / 6; fwrite(&count, 4, 1, stdout); fwrite(out, 4, n, stdout); fflush(stdout);
    }
}
