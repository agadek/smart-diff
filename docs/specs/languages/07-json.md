# JSON

Status: implemented, 2026-11-13

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports JSON files, including JSON with comments and JSON5, through the IDE's JSON parser, when the IDE has JSON support.

**Use case:** A developer reviews a change to a JSON configuration file and sees which [keys](../../GLOSSARY.md#key) changed, were added, removed, moved or renamed, without reading every changed line, while reformatting is [formatting-only](../../GLOSSARY.md#formatting-only).

## Requirements

- **R1** JSON support works when the IDE has JSON support (`com.intellij.modules.json`), and the plugin loads without it.
- **R2** `.json`, JSON with comments (`.jsonc`, and the files the IDE treats as JSON with comments) and JSON5 files are [supported languages](../../GLOSSARY.md#supported-language).
- **R3** Each object member is a [declaration](../../GLOSSARY.md#declaration) of [kind](../../GLOSSARY.md#kind) `KEY`, named by its key without quotes; the members of an object value are its members.
- **R4** A scalar value is the key's content: changing it changes the key.
- **R5** An array is content of its key, except an array whose items are all objects that share one identifying key, `name`, `id` or `key` (the first that every item has, with distinct string or number values): each item is then a `KEY` declaration named `[name=web]`, and its members are the item's members.
- **R6** A top-level object's members are top-level declarations; a top-level array follows the array rule.
- **R7** Items are never matched by position.
- **R8** A reorder of keys is reported as [moved](../../GLOSSARY.md#moved).
- **R9** Whitespace outside strings is formatting-only when nothing else changed; comments are changes.
- **R10** A [key](../../GLOSSARY.md#key) that appears twice in one object follows the matcher's rules for repeated keys.
- **R11** A key renamed with its value unchanged is a [rename candidate](../../GLOSSARY.md#rename-candidate).
- **R12** [Extraction](../../GLOSSARY.md#extraction) and inlining detection do not apply to JSON.
- **R13** The merge outline and the Changes tree show JSON files as they show Java files.
- **R14** JSON has golden fixtures and merge fixtures.

## Out of scope

- Value-aware comparison, such as `1.0` equal to `1` or set semantics for arrays.
- JSON files as source material for another output, or JSON compared only by type.

## References

- [ADR 0009](../../adr/0009-structured-data-keys-are-declarations.md)
- [Format coverage plan §4](../../FORMAT-COVERAGE.md#4-structured-data-group-c)
- [Technical specification §6.12](../../TECHNICAL-SPECIFICATION.md#612-json-provider)
- [JSON details](07-json-details.md)
