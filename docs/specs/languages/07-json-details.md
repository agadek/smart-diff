# JSON: details

Status: implemented, 2026-11-13

**Feature:** [JSON](07-json.md)

## 1. Packaging and IDE versions

- **Plugin:** JSON PSI comes from the platform JSON support, module `com.intellij.modules.json`, bundled with IntelliJ IDEA (R1). The module is present on IntelliJ Platform 2025.1.3 and newer; the optional descriptor is re-checked on 2025.1.3 and the latest release.
- **Descriptor:** the provider registers in `META-INF/smartdiff-json.xml`, loaded through `<depends optional="true" config-file="smartdiff-json.xml">com.intellij.modules.json</depends>` in `plugin.xml`.
- **Build:** `bundledPlugin("com.intellij.modules.json")` in `build.gradle.kts`, so the JSON PSI is on the compile and test classpath; it stays optional at runtime through the optional descriptor.
- **IDE versions:** no change to P6. JSON support needs the `com.intellij.modules.json` module in the IDE.

## 2. Language ids and file types

JSON support in 2025.1.3 provides these languages and file types (R2):

| File | Language id | Notes |
| --- | --- | --- |
| `.json` | `JSON` | `JsonLanguage.INSTANCE` / `JsonFileType` |
| JSON with comments (`.jsonc`, and files the IDE treats as JSON with comments) | `JSON` | The platform treats JSON with comments as the `JSON` language; the provider parses these files with `JsonLanguage` |
| `.json5` | `JSON5` | `Json5Language` |

Spike S10 (JSON part) confirms the language ids and that text-built files give correct key ranges for JSON, JSON with comments and JSON5 on 2025.1.3. The provider's `supports` covers exactly `JSON` and `JSON5`, so other JSON-like languages such as JSON Lines stay unsupported.

## 3. PSI mapping

| PSI element | Declaration | Kind | Name | Members |
| --- | --- | --- | --- | --- |
| `JsonFile` root `JsonObject` | its properties | — | — | top-level declarations (R6) |
| `JsonFile` root `JsonArray` | follows the array rule | — | — | top-level declarations or file content (R6) |
| `JsonProperty` whose value is a scalar or an array treated as content | one declaration | `KEY` | key text without quotes, escapes decoded (R3, R4, R5) | none |
| `JsonProperty` whose value is a `JsonObject` | one declaration | `KEY` | key text without quotes, escapes decoded (R3) | the object's properties |
| `JsonProperty` whose value is an identified-item array | one declaration | `KEY` | key text without quotes, escapes decoded (R3, R5) | each identified item as a `KEY` |

- **Names (R3):** the key as written in the property name, without surrounding quotes; string escapes are decoded to their characters (for example `\"`, `\\`, `\n`, `\u0041`). A key whose text differs only by quoting or escaping style but decodes to the same name is the same name. The key tokens' range is kept as the declaration's `nameRange`, so matching masks the name even though its text differs from the name.
- **Ranges:** a declaration's range is exactly the `JsonProperty`'s text range, or the identified item `JsonObject`'s text range; it never includes the separating comma or the whitespace around it. Offsets equal the request text sliced by the range, including with CRLF line ends (S10). A comma is a separator token (`TokenKind.SEPARATOR`) owned by the containing object key or, at the top level, by the file region. Separators cover source for the nothing-hidden check but are ignored when content is compared, so adding, removing or reordering a key does not make a sibling or its container changed. An unchanged file region that holds separators stays as an unreported node so the nothing-hidden check still covers them.
- **No body, no signature:** `DeclarationInfo.body` is null and `DeclarationInfo.signature` is null. Extraction and inlining detection do not apply (R12).
- **Scalars (R4):** string, number, boolean and null values are content of their key; changing the value changes the key's own tokens and so changes the key.
- **Containers:** an object value makes its key a container whose members are the object's properties, in source order.

## 4. Array identity rule (R5, R7)

An array value is handled as follows, in order:

1. **Identified-item array:** if the array is non-empty and every element is a `JsonObject`, and there is an identifying key `name`, then `id`, then `key` (the first in that order) such that every element object has that key and its value is a string or a number and all those values are distinct, then the array is a container of items:
   - Each element object is a `KEY` declaration.
   - Its name is `[<key>=<value>]`, for example `[name=web]` or `[id=3]`. String values are written without quotes and with escapes decoded; number values are written as in the source value.
   - Its members are the element object's properties.
   - Items are matched by the engine's rules (name and kind in their container), never by position (R7). A reordered list of such items is reported as moved (R8).
2. **Otherwise:** the array, including an empty array, an array of scalars, an array that mixes objects and non-objects, an array of objects without a shared identifying key, or an array whose identifying values are not all distinct strings or numbers, is content of its key. The array's tokens are the key's own tokens.

A top-level array follows the same rule (R6): an identified-item top-level array produces top-level `KEY` declarations for its items; any other top-level array produces no declarations and is file content.

## 5. Whitespace, comments and parse errors

- **Whitespace (R9):** JSON files use `WhitespacePolicy.INSIGNIFICANT`. `OutlineController.whitespacePolicy` maps exactly `JSON` and `JSON5` to `INSIGNIFICANT`, so whitespace outside strings is formatting-only when nothing else changed. Whitespace inside string literals is part of the token text and is compared exactly.
- **Comments (R9):** JSON with comments is the `JSON` language on 2025.1.3, and its comment tokens are compared as token text, so a comment change is a content change, not formatting-only. `JSON Lines` is a separate language and is out of scope.
- **Parse errors:** parse errors follow technical specification §6.7. A file with errors and no declarations while non-blank is `FAILED`; an error inside a declaration's range makes that declaration `UNANALYZED` unless its own tokens and signature are unchanged.

## 6. Repeated keys, renames, moves and relationships (R8, R10, R11, R12)

- **Repeated keys (R10):** a key that appears twice in one object repeats a key and follows the matcher's rules for repeated keys (M1/M4), as for other declarations.
- **Moves (R8):** a reorder of keys is a change of order among siblings and is reported as moved by the engine's order rule. A key moved into another object is moved by container.
- **Rename candidates (R11):** a removed and an added `KEY` with similar content in the same container can pair as a rename candidate by the engine's rename rule (M5). The key's name token is masked through `nameRange`, and a key's value counts as its content even when short, so a key renamed with its value unchanged is a rename candidate and its content stays unchanged.
- **Extraction and inlining (R12):** keys have no bodies (`DeclarationInfo.body = null`), so extraction and inlining detection do not apply. Kind changes (M4b) do not pair a `KEY` with a code kind, since `KEY`, `TAG` and `SECTION` are in no kind family.

## 7. Merges and the Changes tree (R13)

The merge outline and the Changes tree use the engine's result and need no JSON-specific code. JSON has merge fixtures in `src/test/testData/json/merge/` covering the cases the other languages' merge fixtures cover (merge/05 R11), plus one key changed on both sides.

## 8. Engine and corpus

- `DeclarationEngine.VERSION` is raised, since a new provider and kind can change results.
- `CorpusValidationTest` accepts `.json` files (language id `JSON`) so a later corpus run can include them; no corpus run is required now.

## 9. Phases

| Phase | Content | Done when |
| --- | --- | --- |
| D0, D1 | ADR 0009 accepted, `KEY`/`TAG`/`SECTION` kinds, JSON provider, S10 (JSON part), fixtures and tests | R1 to R14 pass in tests named after them, and `./gradlew buildPlugin check` passes |

## 10. Tests

- **Spike S10 (JSON part):** a platform test builds JSON, JSON with comments and JSON5 files from text and checks that for every declaration the PSI element's text equals the request text sliced by its range, also with CRLF line ends.
- **`JsonDeclarationProviderTest`:** one test per mapping rule (R3 to R7, R10); offsets equal the request text for every declaration.
- **Golden fixtures** in `src/test/testData/json/`: value changed, key added, key removed, keys reordered (moved), key moved into another object, key renamed with same value (rename candidate), whole file reformatted (formatting-only), comment changed in JSON with comments (changed), array of scalars changed (parent changed), array of objects with `name` reordered (items moved), array of objects with one item changed, array of objects without a shared identifying key (array is content), duplicate key, parse error on one side, empty and identical files.
- **Merge fixtures** in `src/test/testData/json/merge/`, covering every case of [merge/05](../merge/05-scala-merges.md) R11, plus one key changed on both sides.
- **Requirement names:** every test names its requirement, such as `// languages/07-json R5`.

## 11. Open questions

- **Array identity values:** the rule matches identifying values that are strings or numbers with distinct values; boolean or null identifying values are treated as no identifying key and the array stays content. A later phase may widen this if needed.
