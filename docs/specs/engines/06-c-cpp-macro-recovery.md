# C and C++ macro recovery

Status: implemented, 2026-11-26

**Description:** When the [Tree-sitter engine](05-tree-sitter.md) cannot parse part of a C or C++ file, it parses the file again with [macro recovery](../../GLOSSARY.md#macro-recovery): common macro patterns, such as `FMT_API` before a function or `FMT_BEGIN_NAMESPACE` on a line of its own, are read as blank space, so the grammar finds the declarations around them. Nothing is expanded, and every character is still compared.

**Use case:** A reviewer reads a change to a C library header full of export macros, or to a C++ library that wraps its namespaces and `constexpr` in macros. Today the grammar stops at the first macro it cannot read, and a whole struct, a function next to the changed one, or the whole file is [unanalyzed](../../GLOSSARY.md#unanalyzed). With macro recovery the [structural outline](../../GLOSSARY.md#structural-outline) lists those declarations as it does for code without macros.

## Requirements

- **R1** A C or C++ comparison with a [parse error](../../GLOSSARY.md#parse-error) on either side parses both sides again with macro recovery, and keeps the parse that leaves less source unanalyzed on the two sides together.
- **R2** A comparison whose two sides parse without parse errors is analyzed as without this feature.
- **R3** Both sides of a comparison, and in the [merge window](../../GLOSSARY.md#merge-window) all three versions, use macro recovery, or none of them does.
- **R4** Macro recovery never expands a macro, never reads another file and never needs a macro's definition.
- **R5** A change inside source that macro recovery reads as blank space is a change of the [declaration](../../GLOSSARY.md#declaration) or the [file region](../../GLOSSARY.md#file-region) that holds it.
- **R6** An attribute macro before a declaration, as in `FMT_API void f();`, is part of that declaration.
- **R7** Macro recovery reads as blank space the `#ifndef` and `#endif` lines of an include guard that wraps the whole file.
- **R8** Macro recovery reads as blank space an `#ifdef __cplusplus` block that holds only `extern "C" {` or its closing `}`.
- **R9** Macro recovery reads as blank space an all-capitals macro, with or without arguments, before a declaration's specifiers or type, between `class`, `struct`, `union` or `enum` and the name, or before a constructor once the file uses that macro as an attribute.
- **R10** Macro recovery reads as blank space an all-capitals macro after a parameter list, outside function bodies, as in `void log(const char*, ...) PRINTF_LIKE(1, 2);`.
- **R11** Macro recovery reads as blank space an all-capitals macro invocation that fills its own lines without a `;`, as `FMT_BEGIN_NAMESPACE`, except in an enum body.
- **R12** Macro recovery reads as blank space the head of a macro that takes a block in a function body, as `list_foreach(list, item)` in `list_foreach(list, item) { … }`.
- **R13** Macro recovery reads as blank space a macro call that is a whole template parameter, as `FMT_ENABLE_IF(…)`, with the comma before it.
- **R14** Macro recovery reads as blank space an all-capitals macro between `if` and its condition, as in `if FMT_CONSTEXPR20 (…)`.
- **R15** Macro recovery reads as blank space a `<header>` argument in an `#if` or `#elif` line, as in `#if __has_include(<version>)`.
- **R16** Inside a function body, macro recovery reads each preprocessor conditional's directive lines and every branch after the first as blank space.
- **R17** Macro recovery reads as blank space the directive lines of a conditional without `#else` that holds only specifiers, attribute macros or `extern "C"`, as in `#ifndef _WIN32 static #endif`.
- **R18** A line break that ends a directive line that macro recovery reads as blank space is a change, as in [engines/05](05-tree-sitter.md) R18.
- **R19** The [summary line](../../GLOSSARY.md#summary-line) and the outline show a comparison with macro recovery as any other Tree-sitter comparison.

## Out of scope

- Expanding macros, or reading their definitions from this file or from headers.
- Preprocessor conditionals that split a declaration outside function bodies, as `#if A` `void f(int a) {` `#else` `void f(long a) {` `#endif`.
- Grammar gaps that are not macros, as C++ `extern template` declarations.
- Attribute macros whose names are not all capitals.
- Macro recovery for Swift and C#.

## References

- [Details: design and tests](06-c-cpp-macro-recovery-details.md)
- [Tree-sitter engine](05-tree-sitter.md) R14, R18, R20
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Validation, 2026-11-24](../../VALIDATION.md#2026-11-24-tree-sitter-merge-fixtures-benchmark-cases-and-corpus-validation)
- [Validation, 2026-11-26](../../VALIDATION.md#2026-11-26-c-and-c-macro-recovery)
- [Roadmap: C and C++ macro recovery](../../ROADMAP.md#c-and-c-macro-recovery)
