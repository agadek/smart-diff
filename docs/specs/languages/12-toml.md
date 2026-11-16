# TOML

Status: implemented, 2026-11-15

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports TOML files through the IDE's TOML parser, when the IDE has TOML support.

**Use case:** A developer reviews a change to `Cargo.toml`, `pyproject.toml` or another TOML configuration and sees which tables and [keys](../../GLOSSARY.md#key) changed, were added, removed, moved or renamed, without reading every changed line.

## Requirements

- **R1** TOML support works when the IDE has TOML support (`org.toml.lang`), and the plugin loads without it.
- **R2** `.toml` files, and every file the IDE treats as TOML on IntelliJ Platform 2025.1.3, are [supported languages](../../GLOSSARY.md#supported-language).
- **R3** Each key-value pair is a [declaration](../../GLOSSARY.md#declaration) of [kind](../../GLOSSARY.md#kind) `KEY`, named by its key as written without quotes; a dotted key such as `a.b` is one key named `a.b`.
- **R4** A scalar value is the key's content: changing it changes the key.
- **R5** The pairs of an inline table value are members of its key.
- **R6** An array value is content of its key, except an array whose items are all inline tables that share one identifying key, `name`, `id` or `key` (the first that every item has, with distinct string or number values): each item is then a `KEY` [declaration](../../GLOSSARY.md#declaration) named `[name=web]`, and its members are the item's pairs.
- **R7** Pairs before the first table header are top-level declarations.
- **R8** Each table (`[a.b]`) is a [section](../../GLOSSARY.md#section), a [declaration](../../GLOSSARY.md#declaration) of [kind](../../GLOSSARY.md#kind) `SECTION` named by its header without brackets, such as `a.b`; its pairs are its members, and tables do not nest in each other.
- **R9** Each entry of an array of tables (`[[bin]]`) is a `SECTION` named `bin[name=web]` when every entry of that array has the same identifying key, chosen as in R6; otherwise each entry is named `bin` and follows the matcher's rules for repeated keys.
- **R10** Items and entries are never matched by position.
- **R11** A reorder of keys or tables is reported as [moved](../../GLOSSARY.md#moved).
- **R12** A change in spacing inside a line is [formatting-only](../../GLOSSARY.md#formatting-only) when nothing else changed; a change in line breaks is a change.
- **R13** Comments are changes.
- **R14** A [key](../../GLOSSARY.md#key) or table renamed with its content unchanged is a [rename candidate](../../GLOSSARY.md#rename-candidate).
- **R15** A key or table that appears twice in one container follows the matcher's rules for repeated keys.
- **R16** [Extraction](../../GLOSSARY.md#extraction) and inlining detection do not apply to TOML.
- **R17** [Parse errors](../../GLOSSARY.md#parse-error) on one side behave as for JSON: a file with errors and no declarations while non-blank is not analyzed, and an error inside a declaration's range makes that declaration unanalyzed unless its own tokens are unchanged.
- **R18** The merge outline and the Changes tree show TOML files as they show JSON files.
- **R19** TOML has golden fixtures and merge fixtures.

## Out of scope

- Nesting a table under the table its dotted header extends, or a dotted key under its prefix.
- Schema-aware names, such as Cargo dependencies or Poetry groups, which are tables and keys already.
- Value-aware comparison, such as `1_000` equal to `1000` or a basic string equal to the same literal string.

## References

- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0009](../../adr/0009-structured-data-keys-are-declarations.md)
- [Format coverage plan §4](../../FORMAT-COVERAGE.md#4-structured-data-group-c)
- [Technical specification §6.18](../../TECHNICAL-SPECIFICATION.md#618-toml-provider)
- [TOML details](12-toml-details.md)
