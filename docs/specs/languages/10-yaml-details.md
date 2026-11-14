# YAML: details

Status: implemented, 2026-11-14

**Feature:** [YAML](10-yaml.md)

## 1. Packaging and IDE versions

- **Plugin:** YAML PSI comes from the bundled YAML plugin, `org.jetbrains.plugins.yaml` (R1). The optional descriptor is re-checked on IntelliJ Platform 2025.1.3 and the latest release.
- **Descriptor:** the provider registers in `META-INF/smartdiff-yaml.xml`, loaded through `<depends optional="true" config-file="smartdiff-yaml.xml">org.jetbrains.plugins.yaml</depends>` in `plugin.xml`, after the JSON line.
- **Build:** `bundledPlugin("org.jetbrains.plugins.yaml")` in `build.gradle.kts`, so the YAML PSI is on the compile and test classpath; it stays optional at runtime through the optional descriptor.
- **IDE versions:** no change to P6. YAML support needs the `org.jetbrains.plugins.yaml` plugin in the IDE.

## 2. Language ids and file types

YAML support in 2025.1.3 provides the `yaml` language for `.yaml` and `.yml` files (R2). Spike S10 (YAML part) confirms the language id and that text-built files give correct key and document ranges, including multi-document files, anchors and aliases, merge keys, flow mappings, block scalars and CRLF line ends. The provider's `supports` covers the `yaml` language id.

## 3. PSI mapping

| PSI element | Declaration | Kind | Name | Members |
| --- | --- | --- | --- | --- |
| `YAMLFile` with one `YAMLDocument` | its top-level entries | — | — | top-level declarations (R6) |
| `YAMLFile` with more than one `YAMLDocument` | each document | `SECTION` | document name (section 4) | the document's top-level entries (R6) |
| `YAMLKeyValue` whose value is a scalar, an alias, or a sequence treated as content | one declaration | `KEY` | key text without quotes (R3, R4, R5, R8) | none |
| `YAMLKeyValue` whose value is a `YAMLMapping` | one declaration | `KEY` | key text without quotes (R3) | the mapping's key-value pairs |
| `YAMLKeyValue` whose value is an identified-item `YAMLSequence` | one declaration | `KEY` | key text without quotes (R3, R5) | each identified item as a `KEY` |
| A merge key (`<<`) | one declaration | `KEY` | `<<` (R8) | none |

- **Names (R3):** the key as written, without surrounding single or double quotes. The declaration's `nameRange` is the key element's text range, so matching masks the name token.
- **Ranges:** a key declaration's range is exactly the `YAMLKeyValue`'s text range; a document declaration's range is exactly the `YAMLDocument`'s text range. Offsets equal the request text sliced by the range, including with CRLF line ends (S10).
- **No body, no signature:** `DeclarationInfo.body` is null and `DeclarationInfo.signature` is null. Extraction and inlining detection do not apply (R12).
- **Scalars (R4):** plain, quoted, literal (`|`) and folded (`>`) scalar values are content of their key; changing the value changes the key's own tokens and so changes the key.
- **Containers (R3):** a mapping value makes its key a container whose members are the mapping's key-value pairs, in source order. A flow mapping behaves like a block mapping.
- **Anchors and aliases (R8):** an anchor is part of its anchored node's content. An alias is content as written and is not expanded, so an alias in a value contributes its own tokens to that key. A merge key (`<<`) is a `KEY` named `<<` whose value (mapping or alias or sequence of aliases) is its content.
- **Multi-document separators (R6):** a `---` document start marker is part of the `YAMLDocument`'s range. A file-end marker `...`, when present, is part of the document or file region as the PSI reports it.

## 4. Document naming (R6, R7)

A document is named by the first rule that applies:

1. `kind/metadata.name`, when the document's top-level mapping has a scalar `kind` and a nested mapping `metadata` with a scalar `name`.
2. `name` or `id` at the document's top level, when present as a scalar.
3. `document N` (1-based, in source order).

The `document N` fallback is the one positional match, because an unnamed document has no stable identity in the file. A single-document file has no section (R6), so its top-level entries are top-level declarations. When a file goes from one document to several, the old top-level keys match keys inside the new section by name, as moved, and the section itself is added (R7). A named document keeps its name when the file becomes multi-document. Unnamed documents in a multi-document file use the positional `document N` fallback in source order.

## 5. Sequence identity rule (R5)

A sequence value is handled as follows, in order:

1. **Identified-item sequence:** if the sequence is non-empty and every item is a mapping, and there is an identifying key `name`, then `id`, then `key` (the first in that order) such that every item mapping has that key and its value is a scalar and all those values are distinct, then the sequence is a container of items:
   - Each item mapping is a `KEY` declaration.
   - Its name is `[<key>=<value>]`, for example `[name=web]`. Scalar values are written as their text.
   - Its members are the item mapping's key-value pairs.
   - Items are matched by the engine's rules (name and kind in their container), never by position. A reordered list of such items is reported as moved (R9).
2. **Otherwise:** the sequence, including an empty sequence, a sequence of scalars, a sequence that mixes mappings and non-mappings, a sequence of mappings without a shared identifying key, or a sequence whose identifying values are not all distinct scalars, is content of its key.

## 6. Whitespace, comments and parse errors (R9, R13, R14)

- **Whitespace (R13):** YAML files use `WhitespacePolicy.SIGNIFICANT`. `OutlineController.whitespacePolicy` maps the `yaml` language id to `SIGNIFICANT`, so a change in spacing or indentation is not reported as formatting-only. The decision stays `SIGNIFICANT` until golden fixtures show that re-indenting a block in a way that changes nesting is never formatting-only and that spacing changes that keep nesting are; no such rule is established here (ADR 0003). The indentation fixtures record the conservative outcome: a re-indented block is reported as changed.
- **Comments (R9):** comments are compared as token text, so a comment change is a content change.
- **Parse errors (R14):** parse errors follow technical specification §6.7. A file with errors and no declarations while non-blank is `FAILED`; an error inside a declaration's range makes that declaration `UNANALYZED` unless its own tokens and signature are unchanged.

## 7. Repeated keys, renames, moves and relationships (R9, R10, R11, R12)

- **Repeated keys (R11):** a key that appears twice in one mapping repeats a key and follows the matcher's rules for repeated keys (M1/M4).
- **Moves (R9):** a reorder of keys is a change of order among siblings and is reported as moved by the engine's order rule. A key moved into another mapping is moved by container.
- **Rename candidates (R10):** a removed and an added `KEY` with similar content in the same container can pair as a rename candidate by the engine's rename rule (M5). The key's name token is masked through `nameRange`.
- **Extraction and inlining (R12):** keys have no bodies (`DeclarationInfo.body = null`), so extraction and inlining detection do not apply.

## 8. Merges and the Changes tree (R15)

The merge outline and the Changes tree use the engine's result and need no YAML-specific code. YAML has merge fixtures in `src/test/testData/yaml/merge/` covering the cases the other languages' merge fixtures cover (merge/05 R11), plus one key changed on both sides.

## 9. Engine and corpus

- `DeclarationEngine.VERSION` is raised, since a new provider can change results.
- `CorpusValidationTest` accepts `.yaml` and `.yml` files (language id `yaml`) so a later corpus run can include them; no corpus run is required now.

## 10. Phases

| Phase | Content | Done when |
| --- | --- | --- |
| D2 | YAML provider, spike S10 (YAML part), fixtures and tests | R1 to R16 pass in tests named after them, and `./gradlew buildPlugin check` passes |

## 11. Tests

- **Spike S10 (YAML part):** a platform test builds YAML files from text and checks that for every declaration the PSI element's text equals the request text sliced by its range, including a multi-document file, anchors and aliases, merge keys, flow mappings, block scalars and CRLF line ends.
- **`YamlDeclarationProviderTest`:** one test per mapping rule (R3 to R8, R11); offsets equal the request text for every declaration.
- **Golden fixtures** in `src/test/testData/yaml/`: value changed, key added, key removed, keys reordered (moved), key moved into another mapping, key renamed with same value (rename candidate), comment changed, block scalar changed, sequence of scalars changed (parent changed), sequence of mappings with `name` reordered (items moved), one item changed, sequence without a shared identifying key (content), multi-document file with a Kubernetes-style document changed and documents reordered, one-to-two documents (R6, R7), anchor value changed with an alias user, merge key, flow mapping, duplicate key, indentation cases (re-indent changing nesting is changed; spacing that keeps nesting is changed under the `SIGNIFICANT` decision), parse error on one side, empty and identical files.
- **Merge fixtures** in `src/test/testData/yaml/merge/`, covering every case of [merge/05](../merge/05-scala-merges.md) R11, plus one key changed on both sides.
- **Relationship benchmark:** rename cases only, as JSON did.
- **Requirement names:** every test names its requirement, such as `// languages/10-yaml R5`.

## 12. Open questions

- **Alias expansion:** aliases and merge keys are compared as written, not expanded; expanding them would need a dedicated rule and could hide which key changed.
- **Schema-aware names:** GitHub Actions jobs and docker-compose services are mapping keys and are already named by the key; schema-specific naming is out of scope.