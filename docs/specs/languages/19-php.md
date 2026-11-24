# PHP

Status: implemented, 2026-11-24

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports PHP files, through the JetBrains PHP plugin's parser, when the IDE has the PHP plugin.

**Use case:** A developer reviews a PHP change in PhpStorm, or in IntelliJ IDEA Ultimate with the PHP plugin, and sees which namespaces, classes, interfaces, traits, enums, functions, methods, properties and constants changed, without reading every changed line.

## Requirements

- **R1** PHP support works when the IDE has the PHP plugin, and the plugin loads without it.
- **R2** `.php` files are a [supported language](../../GLOSSARY.md#supported-language) when the PHP plugin is present.
- **R3** Top-level classes, interfaces, traits, enums, functions and constants, `const` and `define()` ones, are [declarations](../../GLOSSARY.md#declaration).
- **R4** A named namespace, in statement or braced form, is a declaration that contains the declarations in it; the declarations of a namespace block without a name are top-level.
- **R5** The methods, properties, class constants and enum cases of a class, interface, trait or enum are declarations, and members of their type; `__construct` is a constructor.
- **R6** Each property or constant written together in one statement, such as `public $a, $b;`, is its own declaration.
- **R7** Declarations inside a function body, closures, arrow functions, anonymous classes, `use` imports, trait uses and inline HTML are not declarations.
- **R8** A declaration's range includes the PHPDoc and comment lines directly above it and its attributes.
- **R9** A function's, method's or constructor's [signature](../../GLOSSARY.md#signature) is each parameter's type, `&` and `...` markers and name, without default values, attributes and the return type; a promoted constructor property is part of its constructor.
- **R10** Whitespace outside string literals, heredoc and nowdoc bodies is [formatting-only](../../GLOSSARY.md#formatting-only) when nothing else changed.
- **R11** A whitespace change inside a string literal, a heredoc or a nowdoc body is a change.
- **R12** Extraction detection, inlining detection, rename candidates, kind changes and moves inside declarations work for PHP as for the other languages.
- **R13** The merge outline and the Changes tree show PHP files as they show Java files.
- **R14** PHP has golden fixtures, merge fixtures and relationship benchmark fixtures, as the other languages do.

## Out of scope

- Grouping renames with their usages; S15 showed that in-file references resolve without a PHP interpreter, so a `PhpReferenceResolver` is its own change to [relationships/04](../relationships/04-rename-grouping.md).
- Blade, Twig and other template languages, and files that are mostly inline HTML; their HTML is content of the file region.
- Conditional declarations, such as a function declared inside `if (!function_exists(…))`; they are content of the code around them.

## References

- [Details](19-php-details.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0011](../../adr/0011-rust-provider-build-target-spike.md)
- [Format coverage plan, phase B4](../../FORMAT-COVERAGE.md#5-phases)
- [Roadmap: More languages](../../ROADMAP.md#more-languages)
