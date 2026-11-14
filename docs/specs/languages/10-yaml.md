# YAML

Status: implemented, 2026-11-14

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports YAML files through the IDE's YAML parser, when the IDE has YAML support.

**Use case:** A developer reviews a change to a YAML configuration or manifest and sees which [keys](../../GLOSSARY.md#key) and documents changed, were added, removed, moved or renamed, without reading every changed line.

## Requirements

- **R1** YAML support works when the IDE has YAML support (`org.jetbrains.plugins.yaml`), and the plugin loads without it.
- **R2** `.yaml` and `.yml` files, and every file the IDE treats as YAML on IntelliJ Platform 2025.1.3, are [supported languages](../../GLOSSARY.md#supported-language).
- **R3** Each mapping entry is a [declaration](../../GLOSSARY.md#declaration) of [kind](../../GLOSSARY.md#kind) `KEY`, named by its key without quotes; the entries of a nested mapping value are its members. A flow mapping (`{a: 1}`) behaves like a block mapping.
- **R4** A scalar value (plain, quoted, literal `|` or folded `>` block) is the key's content: changing it changes the key.
- **R5** A sequence is content of its key, except a sequence whose items are all mappings that share one identifying key, `name`, `id` or `key` (the first that every item has, with distinct scalar values): each item is then a `KEY` [declaration](../../GLOSSARY.md#declaration) named `[name=web]`, and its members are the item's entries. Items are never matched by position.
- **R6** A single-document file's top-level entries are top-level declarations. In a multi-document file (`---` separators) each document is a [declaration](../../GLOSSARY.md#declaration) of [kind](../../GLOSSARY.md#kind) `SECTION` whose members are its top-level entries.
- **R7** A document is named by `kind/metadata.name` when both exist, else by `name` or `id` at its top level, else `document N` (1-based). The `document N` fallback is the one positional match, because an unnamed document has no stable identity; a single-document file uses the same rule.
- **R8** An anchor (`&base`) is part of its node's content; an alias (`*base`) is content as written and is not expanded, so changing an anchored value changes only the anchored key. A merge key (`<<: *base`) is a `KEY` named `<<`.
- **R9** Comments are changes. A reorder of keys is reported as [moved](../../GLOSSARY.md#moved).
- **R10** A [key](../../GLOSSARY.md#key) renamed with its value unchanged is a [rename candidate](../../GLOSSARY.md#rename-candidate).
- **R11** A [key](../../GLOSSARY.md#key) that appears twice in one mapping follows the matcher's rules for repeated keys.
- **R12** [Extraction](../../GLOSSARY.md#extraction) and inlining detection do not apply to YAML.
- **R13** Whitespace is compared as significant: a change in spacing or indentation is not reported as [formatting-only](../../GLOSSARY.md#formatting-only) until a tested indentation rule exists.
- **R14** Parse errors on one side behave as for JSON: a file with errors and no declarations while non-blank is not analyzed, and an error inside a declaration's range makes that declaration unanalyzed unless its own tokens and signature are unchanged.
- **R15** The merge outline and the Changes tree show YAML files as they show JSON files.
- **R16** YAML has golden fixtures and merge fixtures.

## Out of scope

- Expanding aliases or merge keys to the values they refer to.
- Schema-aware names for entries such as GitHub Actions jobs or docker-compose services, which are mapping keys already.
- Value-aware comparison.

## References

- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0009](../../adr/0009-structured-data-keys-are-declarations.md)
- [Format coverage plan §4](../../FORMAT-COVERAGE.md#4-structured-data-group-c)
- [Technical specification §6.13](../../TECHNICAL-SPECIFICATION.md#613-yaml-provider)
- [YAML details](10-yaml-details.md)
