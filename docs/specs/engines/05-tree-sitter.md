# Tree-sitter engine

Status: implemented, 2026-11-24 (Swift 2026-11-18; C#, C and C++, and the validation of T3, 2026-11-24)

**Description:** A [comparison engine](../../GLOSSARY.md#comparison-engine) that parses Swift, C#, C and C++ files with bundled Tree-sitter grammars when the IDE has no language support for them, and gives them the same [structural outline](../../GLOSSARY.md#structural-outline) as the languages the IDE parses. These files are [Tree-sitter languages](../../GLOSSARY.md#tree-sitter-language).

**Use case:** A developer keeps an iOS app's Swift sources, or a native C or C++ library, in the same repository as a Kotlin backend, and reviews changes to both in IntelliJ IDEA. Today the Swift and C++ files get only the native diff, or the [line comparison](../../GLOSSARY.md#line-comparison). With this engine the outline lists which functions, types and properties changed, so the reviewer reads those files the way they read the Kotlin ones.

## Requirements

- **R1** "Compare languages the IDE cannot parse with Tree-sitter" is on by default, and with it off, diffs behave as without this feature.
- **R2** Swift, C#, C and C++ files that the IDE opens as plain text or as TextMate files are [supported languages](../../GLOSSARY.md#supported-language).
- **R3** The language is chosen by the file name's extension: `.swift` is Swift, `.cs` is C#, `.c` is C, and `.cpp`, `.cc`, `.cxx`, `.c++`, `.h`, `.hpp`, `.hh` and `.hxx` are C++.
- **R4** A file whose language the IDE parses with its own language support is never compared by the Tree-sitter engine.
- **R5** A Tree-sitter language is never compared by the line comparison.
- **R6** The structural outline, the [Changes tree](../../GLOSSARY.md#changes-tree), [three-sided diffs](../../GLOSSARY.md#three-sided-diff) and the merge outline show Tree-sitter languages as they show Java files.
- **R7** The [summary line](../../GLOSSARY.md#summary-line) of a Tree-sitter language ends with `· tree-sitter`.
- **R8** In Swift, classes, structs, enums, enum cases, protocols, extensions, actors, functions, initializers, deinitializers, subscripts, properties and type aliases are [declarations](../../GLOSSARY.md#declaration).
- **R9** In C#, namespaces, classes, structs, records, interfaces, enums, enum members, delegates, methods, constructors, finalizers, operators, properties, indexers, events and fields are declarations.
- **R10** In C, functions, function prototypes, structs, unions, enums, enumerators, struct and union fields, `typedef` names, `#define` macros and variables at file level are declarations.
- **R11** In C++, the C declarations, namespaces, classes, methods, constructors, destructors, fields, `using` aliases and templates are declarations.
- **R12** A C++ method defined outside its class, as `Type::name`, is a declaration of the container that holds the definition, named `Type::name`.
- **R13** Nothing inside a function, method or property body is a declaration.
- **R14** A declaration inside a preprocessor conditional is a declaration of the container that holds the conditional.
- **R15** A declaration's range includes its attributes and the doc comment directly above it.
- **R16** A function's [signature](../../GLOSSARY.md#signature) is its parameter types, without names and default values, and in Swift each parameter's argument label before its type.
- **R17** In C#, C and C++, whitespace and line breaks outside string literals and preprocessor directives are [formatting-only](../../GLOSSARY.md#formatting-only) when nothing else changed.
- **R18** In C and C++, a line break that ends or continues a preprocessor directive is a change.
- **R19** In Swift, a line break is formatting-only where it is formatting-only in Kotlin.
- **R20** Source that a grammar cannot parse is [unanalyzed](../../GLOSSARY.md#unanalyzed), and the rest of the file is analyzed; in C and C++, [macro recovery](06-c-cpp-macro-recovery.md) parses around common macro patterns first.
- **R21** Rename candidates, kind changes, moves, extraction detection and inlining detection work for Tree-sitter languages as for Java.
- **R22** The engine works in the IDE on macOS, Linux and Windows, on x64 and arm64.
- **R23** When the parser cannot be loaded, Tree-sitter languages behave as with the setting off, and the IDE log has one warning for the session.
- **R24** A grammar that fails on any input shows `Analysis failed`, and the IDE keeps running.
- **R25** A file over the [analysis limits](../../GLOSSARY.md#analysis-limits) shows `Too large to analyze`, and a comparison over the time limit shows `Analysis failed` with `timed out`.
- **R26** The parser and the grammars ship inside the plugin, and the plugin downloads nothing to parse a file.
- **R27** Every bundled grammar and the parser runtime have a licence that allows them to ship in the plugin, and each is listed with its version and licence in the plugin's third-party notices.
- **R28** The plugin archive grows by at most 25 MB with the four languages.
- **R29** The engine's version names each grammar's version.
- **R30** Each Tree-sitter language has golden fixtures, merge fixtures and relationship benchmark fixtures, as the other languages do.
- **R31** The setting is a checkbox on the settings page, and changing it there applies to open diffs when applied.

## Out of scope

- C#, C and C++ in Rider and CLion Nova, where the IDE has language support in its backend; a later change decides them with spike S12's findings.
- Objective-C, F#, VB.NET and other languages, which can follow with their own grammars.
- Grouping renames with their usages, which needs reference resolution that Tree-sitter does not provide.
- Choosing between C and C++ for a `.h` file by its contents.
- Expanding macros, or declarations that only a macro generates; [macro recovery](06-c-cpp-macro-recovery.md) reads common macro patterns as blank space instead.
- Fine-grained differencing with Tree-sitter, and with it [moves inside declarations](../relationships/08-moves-inside-declarations.md), which read the changed runs; and Tree-sitter for languages the IDE parses.

## References

- [Details: design and tests](05-tree-sitter-details.md)
- [ADR 0002](../../adr/0002-parser-neutral-model-with-psi-first.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0004](../../adr/0004-pluggable-comparison-engines.md)
- [ADR 0015](../../adr/0015-tree-sitter-languages-are-chosen-by-file-name.md)
- [ADR 0016](../../adr/0016-tree-sitter-runs-in-a-native-helper-process.md)
- [Engine contract](02-engine-contract.md) R13
- [Comparison engines](01-comparison-engines.md)
- [Moved lines in unsupported files](04-moved-lines.md) R4
- [Kotlin and Scala formatting](../declarations/07-kotlin-scala-formatting.md)
- [Format coverage plan](../../FORMAT-COVERAGE.md#25-group-e-not-supported-and-why)
- [Research: Difftastic and alternatives](../../RESEARCH.md#difftastic-and-alternatives)
- [Roadmap: Tree-sitter support](../../ROADMAP.md#tree-sitter-support)
- [C and C++ macro recovery](06-c-cpp-macro-recovery.md)
