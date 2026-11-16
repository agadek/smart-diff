# Kotlin and Scala formatting

Status: implemented, 2026-11-16

**Description:** In Kotlin and Scala, a [declaration](../../GLOSSARY.md#declaration) whose own source differs only in line breaks the language ignores, such as a wrapped argument list or a split call chain, is [formatting-only](../../GLOSSARY.md#formatting-only) instead of [changed](../../GLOSSARY.md#changed).

**Use case:** A reviewer opens a Kotlin diff where the formatter wrapped long parameter lists and call chains in several functions, and one function got a real fix. Today every wrapped function shows as changed. With this feature only the fix is changed, and the wrapped functions are grouped with the other formatting-only changes.

## Requirements

- **R1** In Kotlin and Scala 2, adding or removing a line break inside parentheses or square brackets is formatting-only.
- **R2** In Kotlin and Scala 2, adding or removing a line break directly after a comma, an opening parenthesis, square bracket or brace, `=`, `->`, `=>`, `.`, or a binary operator that cannot end an expression, is formatting-only.
- **R3** In Kotlin and Scala 2, adding or removing a line break directly before a closing parenthesis, square bracket or brace, a comma, `.`, `?.`, `?:`, `&&` or `||` is formatting-only.
- **R4** A line break inside braces or a lambda body that is itself inside parentheses is compared as in a block, not as in the parentheses around it.
- **R5** In Kotlin and Scala 2, adding or removing any other line break keeps the declaration changed.
- **R6** In Scala 3, adding or removing a line break directly after a comma or an opening parenthesis or square bracket, or directly before a closing one, is formatting-only, and so is the indentation after such a line break.
- **R7** In Scala 3, every other line break and the indentation after it are compared as before.
- **R8** In Scala 3, a declaration whose every line was re-indented by the same amount, with nothing else changed, is formatting-only.
- **R9** A declaration with a [parse error](../../GLOSSARY.md#parse-error) on either side is never formatting-only under these rules.
- **R10** A [rename candidate](../../GLOSSARY.md#rename-candidate) and its grouped [usages](../../GLOSSARY.md#usage) treat these line breaks as formatting too, so a renamed declaration that was only wrapped differently has nothing changed but the rename.
- **R11** Java, Python, Rust and every other language classify their declarations as before.
- **R12** The [relationship benchmark](../../RELATIONSHIP-BENCHMARK.md) shows no case labelled changed that is now formatting-only, and no lower formatting-only recall in any language.

## Out of scope

- Line breaks inside string literals and comments, which are compared as written.
- Token changes that formatters also make, such as trailing commas, added or removed parentheses, or braces around a single expression.
- Re-indenting part of a Scala 3 declaration by a different amount from the rest.
- Python, whose indentation stays significant as written.

## References

- [Details: design and tests](07-kotlin-scala-formatting-details.md)
- [Change classification](02-change-classification.md) R3, R13, R14
- [Technical specification §8](../../TECHNICAL-SPECIFICATION.md#8-declaration-engine-classification)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Validation, 0.3.0 manual check](../../VALIDATION.md)
- [Roadmap: Formatting-only recall in Kotlin and Scala](../../ROADMAP.md#formatting-only-recall-in-kotlin-and-scala)
