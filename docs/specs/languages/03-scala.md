# Scala

Status: implemented, 2026-10-22

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports Scala 2 and Scala 3 files, through the Scala plugin's parser, when the Scala plugin is installed.

**Use case:** A developer reviews a Scala change and sees which classes, traits, objects and methods changed, whichever Scala version the project uses.

## Requirements

- **R1** Scala support works when the IDE has the Scala plugin, and the plugin loads without it.
- **R2** Classes, traits, objects, package objects, enums and enum cases are [declarations](../../GLOSSARY.md#declaration).
- **R3** `def`, `val`, `var`, `lazy val`, `type`, `given` and `extension` blocks are declarations.
- **R4** An anonymous given is named by its type, such as `Ordering[Int]`.
- **R5** An extension block is named by its receiver type, and its methods are its members.
- **R6** `val (a, b) = …` is one property named `(a, b)`.
- **R7** Local definitions, anonymous classes, statements in a class body and the primary constructor are not declarations.
- **R8** A class and its companion object are separate declarations.
- **R9** A [signature](../../GLOSSARY.md#signature) joins the parameter clauses with `;` and keeps `using` and `implicit`.
- **R10** Both sides of a diff are read as the same Scala version.
- **R11** Code without Scala 3 syntax is read as Scala 2.
- **R12** In Scala 2, adding or removing a line break between tokens inside a declaration is a change; the line break before a declaration is layout ([declarations/02-change-classification](../declarations/02-change-classification.md) R14).
- **R13** In Scala 3, a change of indentation is a change, so moving from braces to indentation is never [formatting-only](../../GLOSSARY.md#formatting-only).
- **R14** Errors in Scaladoc markup are not [parse errors](../../GLOSSARY.md#parse-error).

## References

- [Technical specification §6.2 and §6.8](../../TECHNICAL-SPECIFICATION.md#68-scala-provider)
- [Validation, Scala](../../VALIDATION.md#2026-10-22-scala)
