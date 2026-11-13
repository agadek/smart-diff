# Python

Status: proposed, 2026-11-13

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports Python files, through the Python plugin's parser, when the IDE has the Python plugin.

**Use case:** A developer reviews a Python change in PyCharm, or in IntelliJ IDEA with the Python plugin, and sees which classes, functions and methods changed, without reading every changed line, while re-indented code is never called [formatting-only](../../GLOSSARY.md#formatting-only).

## Requirements

- **R1** Python support works when the IDE has the Python plugin, and the plugin loads without it.
- **R2** `.py` and `.pyi` files are a [supported language](../../GLOSSARY.md#supported-language) when the Python plugin is present.
- **R3** Classes and functions (`def` and `async def`) are [declarations](../../GLOSSARY.md#declaration), including methods and nested classes.
- **R4** A name assigned at module level or directly in a class body, such as `MAX = 3` or `x: int`, is a declaration.
- **R5** `a, b = …` at module level or in a class body is one declaration named `(a, b)`.
- **R6** A `type` alias statement is a declaration.
- **R7** A class or function defined inside an `if`, `try`, `with` or `for` at module level or in a class body is a declaration of that module or class.
- **R8** Definitions inside a function body, lambdas, comprehensions, instance attributes assigned through `self`, imports and other statements are not declarations.
- **R9** A declaration's range includes its decorators and its comments right above it.
- **R10** Adding, removing or changing a decorator changes the declaration.
- **R11** A function's [signature](../../GLOSSARY.md#signature) is its parameter names in order, with `*`, `**` and `/` markers, without annotations and default values.
- **R12** Two definitions with the same name in one container, such as `@overload` variants or a property getter and setter, are separate declarations.
- **R13** A change of a line's indentation is a change, never formatting-only.
- **R14** Adding or removing a line break between tokens inside a declaration is a change; the line break before a declaration is layout ([declarations/02-change-classification](../declarations/02-change-classification.md) R14).
- **R15** Whitespace inside a line, outside string literals, is formatting-only when nothing else changed.
- **R16** A docstring change is a change, not a comment-only change.
- **R17** Both sides of a diff are parsed at the newest Python version the Python plugin knows, whatever the project's interpreter.
- **R18** Extraction detection, inlining detection, rename candidates and kind changes work for Python as for the other languages.
- **R19** Renames are grouped with their usages in the same file, as in [relationships/04](../relationships/04-rename-grouping.md).
- **R20** The merge outline and the Changes tree show Python files as they show Java files.
- **R21** Python has golden fixtures, merge fixtures and relationship benchmark fixtures, as the other languages do.

## Out of scope

- Jupyter notebooks, Cython and other Python dialects.
- Python 2 syntax that Python 3 no longer accepts; it is a [parse error](../../GLOSSARY.md#parse-error).
- Treating a consistent change of indentation width, such as 4 spaces to 2, as formatting-only.
- Usages in other files.

## References

- [Details](04-python-details.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Roadmap: Python](../../ROADMAP.md#python)
