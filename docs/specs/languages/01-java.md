# Java

Status: implemented, 2026-10-21

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports Java files, through the IDE's Java parser.

**Use case:** A developer reviews a Java change in IntelliJ IDEA and sees which classes, methods and fields changed.

## Requirements

- **R1** Java support works when the IDE has the Java plugin, and the plugin loads without it.
- **R2** Classes, interfaces, enums, records and annotation types are [declarations](../../GLOSSARY.md#declaration).
- **R3** Constructors, methods, fields, enum constants and initializer blocks are declarations.
- **R4** Fields declared together, such as `int a, b;`, are separate declarations.
- **R5** Anonymous classes, local classes, lambdas, local variables and record components are not declarations.
- **R6** The members of an implicitly declared class are top-level declarations.
- **R7** A [signature](../../GLOSSARY.md#signature) is the parameter types as written, without names, annotations or modifiers.
- **R8** Whitespace between tokens is never a change in Java, so reformatting is [formatting-only](../../GLOSSARY.md#formatting-only).
- **R9** Files are parsed at the newest Java language level the IDE knows.

## References

- [Technical specification §6.4 and §6.6](../../TECHNICAL-SPECIFICATION.md#64-java-provider)
