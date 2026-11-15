# Properties

Status: accepted, 2026-11-15

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports Java properties files through the IDE's properties parser, when the IDE has properties support.

**Use case:** A developer reviews a change to `application.properties` or a message bundle and sees which [keys](../../GLOSSARY.md#key) changed, were added, removed, moved or renamed, without reading every changed line.

## Requirements

- **R1** Properties support works when the IDE has properties support (`com.intellij.properties`), and the plugin loads without it.
- **R2** `.properties` files, and every file the IDE treats as a properties file on IntelliJ Platform 2025.1.3, are [supported languages](../../GLOSSARY.md#supported-language).
- **R3** Each property is a [declaration](../../GLOSSARY.md#declaration) of [kind](../../GLOSSARY.md#kind) `KEY`, named by its key as the IDE reads it, with escapes resolved.
- **R4** Keys do not nest: a dotted key such as `spring.datasource.url` is one key, and every key is a top-level declaration.
- **R5** The value is the key's content: changing it changes the key.
- **R6** A reorder of keys is reported as [moved](../../GLOSSARY.md#moved).
- **R7** A change in spacing around the `=` or `:` separator, or in blank lines between keys, is [formatting-only](../../GLOSSARY.md#formatting-only) when nothing else changed.
- **R8** A change of separator character, a change in spacing inside a value, and a value split or joined with line continuations are changes.
- **R9** Comments are changes.
- **R10** A [key](../../GLOSSARY.md#key) renamed with its value unchanged is a [rename candidate](../../GLOSSARY.md#rename-candidate).
- **R11** A [key](../../GLOSSARY.md#key) that appears twice in one file follows the matcher's rules for repeated keys.
- **R12** [Extraction](../../GLOSSARY.md#extraction) and inlining detection do not apply to properties files.
- **R13** [Parse errors](../../GLOSSARY.md#parse-error) on one side behave as for JSON: a file with errors and no declarations while non-blank is not analyzed, and an error inside a declaration's range makes that declaration unanalyzed unless its own tokens are unchanged.
- **R14** The merge outline and the Changes tree show properties files as they show JSON files.
- **R15** Properties files have golden fixtures and merge fixtures.

## Out of scope

- Grouping dotted keys into sections by their prefix.
- Comparing values with escapes or line continuations resolved.
- Linking a key to its translations in the other files of a resource bundle.
- File encodings other than the one the diff already uses.

## References

- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0009](../../adr/0009-structured-data-keys-are-declarations.md)
- [Format coverage plan §4](../../FORMAT-COVERAGE.md#4-structured-data-group-c)
- [Technical specification §6.17](../../TECHNICAL-SPECIFICATION.md#617-properties-provider)
- [Properties details](11-properties-details.md)
