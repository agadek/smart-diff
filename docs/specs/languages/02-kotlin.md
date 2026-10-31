# Kotlin

Status: implemented, 2026-10-21

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports Kotlin files, through the IDE's Kotlin parser, in K1 and K2 mode.

**Use case:** A developer reviews a Kotlin change and sees which classes, functions and properties changed.

## Requirements

- **R1** Kotlin support works when the IDE has the Kotlin plugin, and the plugin loads without it.
- **R2** Classes, interfaces, enums, annotation classes, objects and companion objects are [declarations](../../GLOSSARY.md#declaration).
- **R3** Functions, secondary constructors, properties, enum entries, type aliases and initializer blocks are declarations.
- **R4** An unnamed companion object is named `Companion`.
- **R5** Local functions and properties, lambdas and object literals are not declarations.
- **R6** The primary constructor and its parameters are part of the class.
- **R7** A function's [signature](../../GLOSSARY.md#signature) starts with its receiver type, then its parameter types.
- **R8** A `vararg` parameter and a plain parameter of the same type give different signatures.
- **R9** An extension's receiver is shown before its name: `function Date.formatIsoDate()`.
- **R10** Adding or removing a line break between tokens is a change, since line breaks can change how Kotlin code is read.
- **R11** Other whitespace changes are [formatting-only](../../GLOSSARY.md#formatting-only).

## References

- [Technical specification §6.5 and §6.6](../../TECHNICAL-SPECIFICATION.md#65-kotlin-provider)
