# Groovy

Status: implemented, 2026-11-14

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports Groovy files, through the IDE's Groovy parser, when the Groovy plugin is installed.

**Use case:** A developer reviews a Groovy change and sees which classes, traits, methods and properties changed, including in Gradle build scripts.

## Requirements

- **R1** Groovy support works when the IDE has the Groovy plugin, and the plugin loads without it.
- **R2** Classes, interfaces, traits, enums and annotation types are [declarations](../../GLOSSARY.md#declaration), including named nested and inner types, with [kind](../../GLOSSARY.md#kind) `TRAIT` for traits and the same kinds as Java for the others.
- **R3** Methods, constructors, fields, properties, enum constants and class initializers are declarations.
- **R4** A field declared without an access modifier is a Groovy property and has kind `PROPERTY`.
- **R5** In a script file, top-level methods and the script's classes are top-level declarations; top-level statements are not declarations and stay in the [file region](../../GLOSSARY.md#file-region); a script's top-level variable declarations are not declarations.
- **R6** Closures, anonymous classes, local variables and local classes are content of the declaration that contains them.
- **R7** A [signature](../../GLOSSARY.md#signature) lists parameter types as written; an untyped parameter appears as `def`, so overloads that differ only by arity get distinct signatures.
- **R8** Whitespace inside a line is never a change in Groovy; adding or removing a line break between tokens inside a declaration is a change, so reformatting inside a line is [formatting-only](../../GLOSSARY.md#formatting-only) and joining or splitting lines inside a declaration is a change.
- **R9** Errors in Groovydoc are not [parse errors](../../GLOSSARY.md#parse-error).
- **R10** `.gradle` files are Groovy files and get the Groovy outline for their methods and classes; their top-level blocks become sections in a later part (M2b) and until then stay in the file region.

## Out of scope

- Build-script sections (`plugins { }`, `dependencies { }` and other top-level blocks in `.gradle` and `.gradle.kts`); modeling them as sections is part M2b, the next step.
- Relationship resolution between Groovy declarations.

## References

- [Details: design and tests](08-groovy-details.md)
- [Technical specification §6.13](../../TECHNICAL-SPECIFICATION.md#613-groovy-provider)
- [Format coverage plan §2.1, §5](../../FORMAT-COVERAGE.md#21-group-a-free-psi-on-the-current-build-target)
