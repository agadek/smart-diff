# C and C++ macro recovery: details

Status: implemented, 2026-11-26

**Feature:** [C and C++ macro recovery](06-c-cpp-macro-recovery.md)

## Design

### 1. Where it plugs in

`TreeSitterEngine.trees` parses every version as before: the old and new text, and in the merge window the other side from `ComparisonRequest.relatedTexts` (R3), which only languages with a recovery parse. When none of the trees has an `ERROR` or `MISSING` node, the engine builds the declaration trees as before and nothing else runs (R2).

Otherwise it asks the language for a `MacroRecovery` of each version (`TreeSitterLanguage.recover`; only `CFamilyLanguage` has one, `CMacroRecovery.recover`). When no version has a pattern, the first parse stands. Else every version with a pattern is parsed again from the recovery's text, a version without one keeps its tree, and the engine builds the declaration trees of both parses. It keeps the parse whose trees leave fewer characters unanalyzed in all versions together, the first parse on a tie (R1, R3). The unanalyzed characters of a tree are, for each declaration with `hasParseError`, its range without its children's ranges, plus the file region's ranges when the file region has a parse error.

The recovery's text has the original's length, and every masked character but `\n` and `\r` is a space, so the grammar's offsets are the original's. `TreeSitterSyntax` keeps the version's own text and the recovery: tokens, names and signatures read the source as written, and only the tree comes from the recovered text (R4).

### 2. What the tree builder does with a recovery

- **Tokens (R5).** A masked range lies between the tree's leaves. `TreeSitterTokens` gives text between leaves that is not whitespace as content tokens of type `TEXT`, one per run of characters that are not whitespace, so a masked macro is compared word by word, and respacing it is formatting-only as for other code. A token belongs to the range that holds its start, as before, so a masked macro is part of the declaration or the file region it lies in.
- **Prefixes (R6).** `MacroRecovery.prefixes` are the masks of R9 before a declaration (not those of a class name, which are inside the class's node) and the opening directive of R17. A declaration's range starts at a prefix that ends before it with only whitespace and at most one line break between, after the previous sibling; doc comments and prefixes are taken alternately, so `/** doc */ FMT_API void f();` starts at the doc comment.
- **Line breaks (R18).** The line break at the end of a masked directive line, and each line break inside one, are in `MacroRecovery.lineBreaks`; `CFamilyLanguage.significantLineBreaks` adds them to those of the directives in the tree, so they are content tokens as in engines/05 §7.

The engine version becomes 3: the same text can give another tree.

### 3. The patterns

#### 3.1 The lexer pass

`CMacroRecovery.lex` reads the text once into identifiers, numbers, string and character literals (with prefixes such as `u8` and raw strings), punctuation (with `::`, `->`, `&&`, `||` and the other two-character operators as one token) and directive lines. A directive starts at a `#` with only whitespace or comments before it on its line and runs to the first line break without a backslash before it. Comments and whitespace are skipped; each token knows whether it starts its line. No rule looks inside a literal, a comment or a directive except R15, so a macro's definition is never changed (R4).

A *macro name* is an identifier of at least two characters in capitals, digits and `_`, starting with a capital or `_` and not ending with `_`, that is not a keyword or one of `NULL`, `EOF`, `TRUE` and `FALSE`. Only macro names are attribute macros (R9 to R11, R14), because a lower-case name in those places is far more often a type.

A *declaration head* at a token is: an optional run of `const` and `volatile`, then a specifier keyword (`static`, `inline`, `extern`, `constexpr`, `void`, `int`, `struct`, `class`, `template`, `typename`, `virtual`, `explicit`, `friend`, `typedef`, `using`, `operator` and the other built-in types and specifiers), or an identifier that is not a keyword followed by an identifier other than `const` and `volatile`, or by `*`, `&`, `&&`, `::` or `<`. So `FMT_API void f()`, `FMT_API const std::string& name()` and `FMT_NO_UNIQUE_ADDRESS Allocator alloc_;` have a head after the macro, and `DWORD count;`, `UINT32 const limit` and `HANDLE open(…)` do not.

#### 3.2 Context

One pass over the tokens tracks braces. A `{` opens a *function body* when the brace before it is in a function body, or when the token before it is `)`, `const`, `noexcept`, `override`, `final`, `mutable`, `else`, `do` or `try`; an *enum body* after `enum` with no `;`, `(`, `)` or `,` between; an *initializer* after `=`, `,`, `(`, `[` or `return`; and another scope otherwise. Directive lines are skipped, and so are the tokens of a conditional's second and later branches, which R16 masks in function bodies, so braces split over `#if … #else … #endif` are counted once.

#### 3.3 Rules

Each rule masks a range of tokens. The directive rules run first; then one pass over the tokens tries, at each identifier, the rules in this order, and skips the masked tokens. The pass runs twice: the first pass learns which macro names the file uses as attributes, which R9's constructor case reads.

| Rule | Masks | Condition |
| --- | --- | --- |
| R7 include guard | the first and the last directive | the first token is `#ifndef X` or `#if !defined(X)`, the second `#define X`, the last the `#endif` that closes the first, with no `#else` or `#elif` at that level |
| R8 `extern "C"` | the whole conditional | `#ifdef __cplusplus` or `#if defined(__cplusplus)` holding exactly `extern "C" {` or exactly `}` |
| R15 header argument | the `<…>` | a `(<…>)` argument in an `#if` or `#elif` line |
| R16 conditional in a body | the opening directive, and everything from the first `#else` or `#elif` to the `#endif` | the opening and the closing directive are in a function body |
| R17 specifier conditional | the opening and closing directive | a conditional without `#else` outside function bodies holding only `const`, `volatile`, specifiers that do not start a declaration of their own (not `struct`, `class`, `union`, `enum`, `namespace`, `typedef`, `using`, `template`, `operator`), `extern "C"`, `__attribute__((…))`, `__declspec(…)` and macro names with their arguments |
| R11 macro line | the macro and its arguments | a macro name that starts its line and a statement (after `;`, `{`, `}`, a directive, an access specifier or another macro line), outside enum bodies and initializers, whose arguments end its last line; the next token is not punctuation other than `}`, `~`, `*`, `&`, `::` or `[`; and the macro, without arguments, is not alone on a line above `name(`, which is a return type, unless the file uses it as an attribute |
| R14 `if` attribute | the macro name | right after `if` and before `(` |
| R9 class attribute | the macro and its arguments | right after `class`, `struct`, `union` or `enum`, and followed by a name and `:`, `{` or `final` |
| R9 attribute | a run of macros with their arguments | not after `.`, `->`, `::` or a class keyword; the run ends at the first macro that is itself a declaration's type, and a declaration head follows it; or the macro is one the file uses as an attribute and a name and `(`, or `~`, follow |
| R10 trailing attribute | the macro and its arguments | outside function bodies, after `)`, `const`, `noexcept`, `override` or `final`, and before `;`, `{`, `=`, `:`, `const`, `noexcept`, `override`, `final`, `->`, `&`, `&&`, `volatile` or another macro |
| R13 template parameter | the macro call and the comma before it, or after it when it is the first parameter | a macro name with arguments directly inside `template <…>`, after `,` or `<`, and before `,` or `>` |
| R12 block macro | a name and its arguments, or a macro name alone | in a function body, at a statement start or after `else`, not a keyword, and followed directly by `{` |

R11 runs before R9, so a macro alone on its line above a declaration, such as `FMT_API` above `void f();`, is a macro line and part of the container, not of `f`.

### 4. Edge cases

- **A clean file with a macro the grammar misreads,** such as `struct LEVELDB_EXPORT Options { … };`, which parses without error as a struct named `LEVELDB_EXPORT`, stays as the grammar reads it (R2). Recovery is not run on files without errors, so a pattern that does not apply can never change a file that parsed.
- **A recovered parse that is worse.** Error recovery in the grammar is not monotonic: masking a macro can let it parse further and fail later on a bigger range. The engine keeps the first parse then (R1).
- **Sides that differ in what is masked.** When a change elsewhere makes a pattern match on one side only, as when the only use of a macro before a keyword is removed and a constructor's prefix is no longer learned, the same words are `TEXT` tokens on one side and grammar tokens on the other, and the declaration is changed. This is conservative ([ADR 0003](../../adr/0003-conservative-classification.md)): it never hides a change.
- **Grammar gaps that are not macros,** such as `extern template` in C++, and conditionals that split a declaration at file level stay unanalyzed (out of scope).
- **Failure.** The recovery is plain string work in the IDE process with no input it cannot handle; any exception it throws is caught by the engine as for other builder errors, and the comparison shows `Analysis failed`.

### 5. Measurements

A prototype of these rules and the implementation were run on the C and C++ sources of jq, fmt, redis, curl, nlohmann/json, leveldb and libuv at their default branches (1,052 files), parsing each file alone with the engine's tree builder: files with unanalyzed source went from 366 to 165 and unanalyzed characters fell by 72%, and no file that parsed without errors was changed, since recovery does not run on them. The corpus validation of real changes is in [VALIDATION.md](../../VALIDATION.md#2026-11-26-c-and-c-macro-recovery).

## Tests

Each test names its requirement, for example `// engines/06 R11`.

- `CMacroRecoveryTest` (unit, text only): each pattern of R7 to R17 with its masked words, and the cases each rule must leave alone: a conditional that is no include guard, capitalized types (`DWORD count;`, `UINT32 const limit`, `HANDLE open(…)`), an invocation with a `;`, an enumerator alone on its line, a return type on its own line, a constructor and statements with a block, a macro after `)` in a function body, a macro call as a function argument, `#include <…>` and `#define` lines, strings and comments (R4); that the recovered text keeps the length and line breaks; the masked directives' line breaks (R18); and which masks are prefixes (R6).
- `CMacroRecoveryTreeTest` (with the helper): a C++ header in fmt's style and a C file in jq's style that fail without recovery are analyzed with their declarations (R1, R7 to R17); an attribute macro and a class attribute are in their declarations' ranges and tokens, and changing one changes the declaration (R5, R6); a masked branch in a body is still the function's own tokens (R5); a file without errors is read as before (R2); a clean side is read with recovery when the other side, or in the merge window the third version, has an error (R3); the line break after a masked include guard is a content token (R18); every character is covered once.
- Golden fixtures `treesitter/c/macro-recovery` and `treesitter/cpp/macro-recovery` in `TreeSitterGoldenTest`: both are `ANALYZED`, and changes next to and inside masked macros (a trailing attribute's arguments, a masked `#else` branch, an attribute macro renamed) show as changed declarations, with an added method beside them (R5, R19).
- `TreeSitterCorpusValidationTest` on the T3 corpus, recorded in VALIDATION.md.
