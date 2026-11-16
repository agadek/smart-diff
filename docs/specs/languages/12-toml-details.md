# TOML: details

Status: implemented, 2026-11-15

**Feature:** [TOML](12-toml.md)

## 1. Packaging and IDE versions

- **Plugin:** TOML PSI comes from the bundled TOML plugin, `org.toml.lang` (R1). The optional descriptor is re-checked on IntelliJ Platform 2025.1.3 and the latest release.
- **Descriptor:** the provider registers in `META-INF/smartdiff-toml.xml`, loaded through `<depends optional="true" config-file="smartdiff-toml.xml">org.toml.lang</depends>` in `plugin.xml`, after the properties line.
- **Build:** `bundledPlugin("org.toml.lang")` is already in `build.gradle.kts`; no build change is needed.
- **IDE versions:** no change to P6.

## 2. Language id and file types

The TOML plugin provides the `TOML` language for `.toml` files, `Cargo.lock` included as the IDE treats it (R2). The provider's `supports` covers that language id. Spike S10 (TOML part) confirms the id and that text-built files give correct ranges, including dotted keys, quoted keys, inline tables, arrays of inline tables, arrays of tables, multi-line strings, comments and CRLF line ends.

## 3. PSI mapping

| PSI element | Declaration | Kind | Name | Members |
| --- | --- | --- | --- | --- |
| `TomlFile` | none | — | — | pairs before the first header (R7), then each table and array-of-tables entry |
| `TomlKeyValue` with a scalar value, or an array treated as content | one declaration | `KEY` | key text (R3) | none |
| `TomlKeyValue` with a `TomlInlineTable` value | one declaration | `KEY` | key text (R3) | the inline table's pairs (R5) |
| `TomlKeyValue` with an identified-item `TomlArray` | one declaration | `KEY` | key text (R3) | each item as a `KEY` named `[name=web]` (R6) |
| `TomlTable` | one declaration | `SECTION` | header key text (R8) | the table's pairs |
| `TomlArrayTable` | one declaration per entry | `SECTION` | section 5 (R9) | the entry's pairs |

- **Key text (R3, R8):** the segments of the `TomlKey` joined with `.`, each segment without its quotes, so `"a".b` and `a.b` are both `a.b`, and spaces around the dots do not count. The declaration's `nameRange` is the key element's text range, so matching masks the name token.
- **Ranges:** a key declaration's range is exactly the `TomlKeyValue`'s text range. A table or array-of-tables entry's range is exactly the `TomlTable` or `TomlArrayTable` element's text range, from its header to its last child as the PSI reports it, so comments inside a table are part of its content. Offsets equal the request text sliced by the range, including with CRLF line ends (S10).
- **No nesting of tables (R8):** `[a]` and `[a.b]` are two top-level sections, since a table's pairs are contiguous only up to the next header. A dotted key stays one key for the same reason (R3).
- **No body, no signature:** `DeclarationInfo.body` is null and `DeclarationInfo.signature` is null (R16).

## 4. Array identity rule (R6, R10)

An array value is handled as follows, in order:

1. **Identified-item array:** if the array is non-empty, every item is an inline table, and there is an identifying key `name`, then `id`, then `key` (the first in that order) that every item has with a string or number value, and those values are distinct, then each item is a `KEY` named `[<key>=<value>]`, with the value's text without quotes, and its members are the item's pairs.
2. **Otherwise:** the array is content of its key.

Items are matched by the engine's rules, never by position (R10).

## 5. Array-of-tables naming (R9, R10)

For each array of tables, taken by its header key text, such as `bin`:

1. If every entry of that array has a top-level pair with one identifying key, `name`, then `id`, then `key` (the first in that order), with a string or number value, and those values are distinct, each entry is named `<header>[<key>=<value>]`, such as `bin[name=cli]`.
2. Otherwise every entry is named `<header>`, and the matcher's rules for repeated keys pair them (M1/M4), never by position.

Entries of one array may be separated by other tables in the file; the rule takes all entries with the same header key text together.

## 6. Whitespace, comments and parse errors (R12, R13, R17)

- **Whitespace (R12):** TOML files use `WhitespacePolicy.LINE_BREAKS_SIGNIFICANT`, the Kotlin policy. `OutlineController.whitespacePolicy` maps the `TOML` language id to it, so spacing within a line is formatting-only and an added or removed line break, a multi-line array included, is a change.
- **Comments (R13):** comments are compared as token text, so a comment change is a content change, in its table or in the file region.
- **Parse errors (R17):** parse errors follow technical specification §6.7. A file with errors and no declarations while non-blank is `FAILED`; an error inside a declaration's range makes that declaration `UNANALYZED` unless its own tokens are unchanged.

## 7. Repeated keys, renames, moves and relationships (R11, R14, R15, R16)

- **Moves (R11):** a reorder of keys in one table, or of tables in the file, is reported as moved by the engine's order rule. A key moved into another table is moved by container.
- **Rename candidates (R14):** a removed and an added `KEY` or `SECTION` with similar content in the same container can pair as a rename candidate by the engine's rename rule (M5).
- **Repeated keys (R15):** a key repeated in one table, or a table header repeated in the file, which TOML forbids but the parser may accept, follows the matcher's rules for repeated keys.
- **Extraction and inlining (R16):** keys and sections have no bodies, so neither applies.

## 8. Merges and the Changes tree (R18)

The merge outline and the Changes tree use the engine's result and need no TOML-specific code. Merge fixtures are in `src/test/testData/toml/merge/` and cover the cases of [merge/05](../merge/05-scala-merges.md) R11, plus one key changed on both sides.

## 9. Engine and corpus

- `DeclarationEngine.VERSION` is raised, since a new provider can change results.
- `CorpusValidationTest` accepts `.toml` files (language id `TOML`), so a later corpus run can include them; no corpus run is required now.
- The structure-view fallback ([languages/06](06-structure-view-fallback.md)) no longer applies to TOML files, since the provider registry picks the dedicated provider first.

## 10. Phases

| Phase | Content | Done when |
| --- | --- | --- |
| D3 (TOML) | TOML provider, spike S10 (TOML part), fixtures and tests | R1 to R19 pass in tests named after them, and `./gradlew buildPlugin check` and `./gradlew verifyPlugin` pass |

## 11. Tests

- **Spike S10 (TOML part):** a platform test builds TOML files from text and checks that for every declaration the PSI element's text equals the request text sliced by its range, with the cases of section 2.
- **`TomlDeclarationProviderTest`:** one test per mapping rule (R3 and R5 to R9, R15); offsets equal the request text for every declaration.
- **Golden fixtures** in `src/test/testData/toml/`: value changed, key added, key removed, keys reordered (moved), tables reordered (moved), key moved into another table, key renamed with the same value (rename candidate), table renamed, dotted key and quoted key, inline table member changed, array of scalars changed (key changed), array of inline tables with `name` reordered (items moved), array of tables with `name` and without, entries reordered, spacing inside a line (formatting-only), line break added in an array (changed), comment changed, multi-line string changed, duplicate key, parse error on one side, empty and identical files.
- **Merge fixtures** in `src/test/testData/toml/merge/`, as in section 8.
- **Relationship benchmark:** rename cases only, as for JSON.
- **Requirement names:** every test names its requirement, such as `// languages/12-toml R9`.

## 12. Open questions

- **Nested tables:** showing `[a.b]` under `[a]` would match how the data nests, but a container's range would no longer be one contiguous stretch of text; the tables stay flat until the engine supports split containers.
