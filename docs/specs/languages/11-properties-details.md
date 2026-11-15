# Properties: details

Status: accepted, 2026-11-15

**Feature:** [Properties](11-properties.md)

## 1. Packaging and IDE versions

- **Plugin:** properties PSI comes from the bundled properties plugin, `com.intellij.properties` (R1). The optional descriptor is re-checked on IntelliJ Platform 2025.1.3 and the latest release.
- **Descriptor:** the provider registers in `META-INF/smartdiff-properties.xml`, loaded through `<depends optional="true" config-file="smartdiff-properties.xml">com.intellij.properties</depends>` in `plugin.xml`, after the YAML line.
- **Build:** `bundledPlugin("com.intellij.properties")` in `build.gradle.kts`, so the properties PSI is on the compile and test classpath; it stays optional at runtime through the optional descriptor.
- **IDE versions:** no change to P6.

## 2. Language id and file types

The properties plugin provides the `Properties` language for `.properties` files (R2). The provider's `supports` covers that language id. Spike S10 (properties part) confirms the id and that text-built files give correct key ranges, including escaped keys, line continuations, `!` and `#` comments, keys without a value and CRLF line ends.

## 3. PSI mapping

| PSI element | Declaration | Kind | Name | Members |
| --- | --- | --- | --- | --- |
| `PropertiesFile` | none | — | — | every property is a top-level declaration (R4) |
| `Property` (`PropertyImpl`) | one declaration | `KEY` | `Property.getUnescapedKey()` (R3) | none |

- **Names (R3):** the key with escapes resolved, so `a\ b` and `a b` are both `a b`. A property without a key is skipped and its text stays in the [file region](../../GLOSSARY.md#file-region). The declaration's `nameRange` is the key element's text range, so matching masks the name token.
- **Ranges:** a key declaration's range is exactly the `Property` element's text range, which covers the key, the separator, the value and its line continuations. Comments are not part of a property: they are siblings in the file and stay in the file region. Offsets equal the request text sliced by the range, including with CRLF line ends (S10).
- **No nesting (R4):** dotted keys stay one key. The outline's tree shows every key at the top level, in source order.
- **No body, no signature:** `DeclarationInfo.body` is null and `DeclarationInfo.signature` is null (R12).

## 4. Whitespace and comments (R7, R8, R9)

- **Policy:** properties files use `WhitespacePolicy.INSIGNIFICANT`. `OutlineController.whitespacePolicy` maps the `Properties` language id to `INSIGNIFICANT`.
- **Separator spacing (R7):** whitespace tokens between the key, the separator and the value are whitespace under that policy, so spacing changes there, and blank lines between properties, are formatting-only when nothing else changed.
- **Inside a value (R8):** the value is compared by its token text, so spacing inside the value, trailing spacing that belongs to the value, and a line continuation added or removed change the key. A fixture records each case. The separator is a token, so `a=1` changed to `a:1` changes the key.
- **Comments (R9):** comments are compared as token text in the file region, so a comment change is a content change.

If S10 shows that the lexer folds separator spacing into the separator or value token, the provider reports the property's tokens without that spacing, and the details file is updated with the token rule before the code.

## 5. Parse errors (R13)

Parse errors follow technical specification §6.7. A file with errors and no declarations while non-blank is `FAILED`; an error inside a declaration's range makes that declaration `UNANALYZED` unless its own tokens are unchanged. The properties lexer accepts almost any line, so S10 records which inputs give error elements at all.

## 6. Repeated keys, renames, moves and relationships (R6, R10, R11, R12)

- **Moves (R6):** a reorder of keys is a change of order among siblings and is reported as moved by the engine's order rule.
- **Rename candidates (R10):** a removed and an added `KEY` with the same or similar value can pair as a rename candidate by the engine's rename rule (M5).
- **Repeated keys (R11):** a key that appears twice in the file follows the matcher's rules for repeated keys (M1/M4).
- **Extraction and inlining (R12):** keys have no bodies, so neither applies.

## 7. Merges and the Changes tree (R14)

The merge outline and the Changes tree use the engine's result and need no properties-specific code. Merge fixtures are in `src/test/testData/properties/merge/` and cover the cases of [merge/05](../merge/05-scala-merges.md) R11, plus one key changed on both sides.

## 8. Engine and corpus

- `DeclarationEngine.VERSION` is raised, since a new provider can change results.
- `CorpusValidationTest` accepts `.properties` files (language id `Properties`), so a later corpus run can include them; no corpus run is required now.
- The structure-view fallback ([languages/06](06-structure-view-fallback.md)) no longer applies to properties files, since the provider registry picks the dedicated provider first.

## 9. Phases

| Phase | Content | Done when |
| --- | --- | --- |
| D3 (properties) | Properties provider, spike S10 (properties part), fixtures and tests | R1 to R15 pass in tests named after them, and `./gradlew buildPlugin check` and `./gradlew verifyPlugin` pass |

## 10. Tests

- **Spike S10 (properties part):** a platform test builds properties files from text and checks that for every declaration the PSI element's text equals the request text sliced by its range, including escaped keys, line continuations, comments, a key without a value and CRLF line ends.
- **`PropertiesDeclarationProviderTest`:** one test per mapping rule (R3, R4, R11); offsets equal the request text for every declaration.
- **Golden fixtures** in `src/test/testData/properties/`: value changed, key added, key removed, keys reordered (moved), key renamed with the same value (rename candidate), escaped key, dotted keys, spacing around the separator (formatting-only), separator character changed, spacing inside a value, line continuation added, comment changed, blank lines changed (formatting-only), duplicate key, parse error on one side if S10 finds one, empty and identical files.
- **Merge fixtures** in `src/test/testData/properties/merge/`, as in section 7.
- **Relationship benchmark:** rename cases only, as for JSON.
- **Requirement names:** every test names its requirement, such as `// languages/11-properties R7`.
