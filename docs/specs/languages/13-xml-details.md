# XML: details

Status: implemented, 2026-11-16

**Feature:** [XML](13-xml.md)

## 1. Packaging and IDE versions

- **Module:** XML PSI is platform support, module `com.intellij.modules.xml` (R1), present in every IntelliJ-based IDE on the current target. The optional descriptor is re-checked on IntelliJ Platform 2025.1.3 and the latest release.
- **Descriptor:** the provider registers in `META-INF/smartdiff-xml.xml`, loaded through `<depends optional="true" config-file="smartdiff-xml.xml">com.intellij.modules.xml</depends>` in `plugin.xml`, after the TOML line. The module is platform, so the guard is a precaution and the provider is available in every supported IDE; the pattern stays the same as the other providers'.
- **Build:** no change to `build.gradle.kts`. The XML PSI is part of the platform, so it is on the compile and test classpath of the current target without a `bundledPlugin` entry; it stays optional at runtime through the optional descriptor.
- **IDE versions:** no change to P6.

## 2. Language id and file types

The platform provides the `XML` language (`XMLLanguage`) for `.xml` files and every file the IDE treats as XML: `pom.xml`, Spring configuration, Android manifests and layouts, `web.xml` and any other XML (R2). The provider's `supports` covers exactly the `XML` language id. `HTML` is a different language id and stays out of scope here; it is D4b, the later part of format coverage D4. Spike S10 (XML part) confirms the id and that text-built files give correct tag ranges, including the prolog, DOCTYPE, namespaces and prefixes, CDATA, comments, processing instructions, mixed content, self-closing tags and CRLF line ends.

## 3. PSI mapping

The provider is an `XmlDeclarationProvider` extending `PsiDeclarationProvider`, in `engine/declarations/psi/xml/`, as `JsonDeclarationProvider` and `PropertiesDeclarationProvider` do.

| PSI element | Declaration | Kind | Name | Members |
| --- | --- | --- | --- | --- |
| `XmlFile` | none | — | — | the root tag (R6) |
| `XmlTag` | one declaration | `TAG` | the naming rules of section 4 (R3, R4, R5) | its child tags, `XmlTag.getSubTags()` (R6) |
| `XmlAttribute`, `XmlText`, CDATA, `XmlComment`, `XmlProcessingInstruction` inside a tag | none | — | — | content of their tag (R7) |
| `XmlProlog`, `XmlDoctype`, comments and processing instructions outside the root tag | none | — | — | the [file region](../../GLOSSARY.md#file-region) (R15) |

- **Top level (R6):** `topLevel` returns `XmlFile.getRootTag()` as the single top-level element. The root tag is a named tag, so it is a declaration, unlike a JSON root object; its attributes are its content. A file without a root tag has no declarations, and the parse-error rules of section 8 apply.
- **Ranges:** a tag declaration's range is exactly the `XmlTag`'s text range, from its start tag to its end tag, or the whole self-closing tag. Offsets equal the request text sliced by the range, including with CRLF line ends (S10).
- **Members (R6):** only child tags are members. Attributes, text, comments, CDATA and processing instructions inside a tag are its own tokens, not declarations, so a tag with only text, such as `<version>4.13</version>`, is one declaration whose content is the text.
- **No body, no signature:** `DeclarationInfo.body` is null and `DeclarationInfo.signature` is null (R16).

## 4. Naming (R3, R4, R5)

A tag is named by the first rule that applies:

1. **POM coordinates (R5):** the tag name is `dependency` or `plugin`, and it has direct child tags `groupId` and `artifactId`, both with non-empty trimmed text. The name is `<tag> <groupId>:<artifactId>`, such as `dependency org.junit.jupiter:junit-jupiter`. The values are the child tags' text content, trimmed, as written; a property placeholder such as `${junit.version}` stays as written. The rule reads the content, not the file name, since the request's file name is a hint only, and it names POM tags in any file.
2. **`id` attribute (R4):** the tag has an `id` attribute with a non-empty value. The name is `<tag> id=<value>`, such as `bean id=dataSource`.
3. **`name` attribute (R4):** the tag has a `name` attribute with a non-empty value. The name is `<tag> name=<value>`, such as `property name=url`.
4. **Tag name (R3):** otherwise the tag name as written, prefix included, such as `beans:bean` or `version`.

- **Values:** the attribute value without its quotes, as written; entity references are not resolved. An empty attribute value does not name the tag, and the next rule applies.
- **Prefixes (R3):** the tag name keeps its prefix, so `beans:bean` and `bean` are different names. Namespace declarations (`xmlns`, `xmlns:beans`) are attributes and so content (R7). No namespace URI is resolved.
- **`nameRange`:** for rules 2 and 3, the identifying attribute's value element's text range, as for a JSON identified item and a TOML array-of-tables entry, so matching masks the value token and an `id` or `name` rename leaves the content unchanged. For rule 1, null: the coordinates are member tags whose own content carries a change. For rule 4, null: the engine masks tokens whose text equals the name, and the XML lexer writes each tag name as a name token (S10 confirms); if S10 finds otherwise, the provider sets `nameRange` to the start tag's name token.
- **A version bump (R5):** the coordinates name the dependency, so a version bump matches the same dependency and changes only its `version` child tag: one changed dependency, not a changed `dependencies` block and not an uncertain repeated `dependency`.

## 5. Content, attributes and order (R7, R8, R11, R12)

- **Attributes (R7, R8):** a tag's attributes are its own tokens. A reordered attribute changes the token order and so changes the tag; this resolves F4 as proposed, since attributes are content and attribute-level declarations would crowd the outline. An added, removed or changed attribute changes the tag the same way.
- **Child tag order (R11):** FORMAT-COVERAGE §4 keeps XML child tag order as content: the order of child tags is significant and is never formatting noise. Child tags are members, so the engine's order rule reports a reorder as moved, the same label it uses for identified array items; the filter can hide moved changes. A tag moved into another container is moved by container.
- **Repeated tags (R12):** sibling tags with one name, such as several unnamed `constructor-arg` tags or two beans with one `id`, repeat a key and follow the matcher's rules for repeated keys (M1/M4). Tags are never matched by position.
- **Text, comments, CDATA, processing instructions (R7):** inside a tag they are its own tokens and are compared as content; a change to them changes the tag. Comments compare by exact text, as in every language (technical specification §8).

## 6. Whitespace and text (R9, R10)

- **Policy:** XML files use `WhitespacePolicy.INSIGNIFICANT`. `OutlineController.whitespacePolicy` maps the `XML` language id to `INSIGNIFICANT`, so spacing inside a tag, between attributes and between child tags, is formatting-only when nothing else changed (R10).
- **Exact text (R9):** the provider overrides `significantWhitespace(file)` to return the ranges of text content that compares exactly: every `XmlText` whose text holds at least one non-whitespace character, from its first to its last non-whitespace character, and every CDATA section. Trimming keeps the indentation before and after the text as layout, so re-indenting `<a>\n  text\n</a>` stays formatting-only. `LexedTokens` promotes whitespace tokens that start in those ranges to `TokenKind.OTHER`, so whitespace normalization never drops them and text spacing compares exactly. Whitespace-only `XmlText` is left out, so re-indenting a file stays formatting-only. S10 records how the XML lexer splits character data and whitespace and how the parser reports CDATA.
- **Content whitespace:** the XML lexer writes whitespace in tag content as its own token type, which the parser definition does not list as whitespace. The provider's `tokenKind` treats every blank token as `TokenKind.WHITESPACE`, so it is layout under `INSIGNIFICANT` unless it falls in one of the exact ranges above.
- **Consequences:** re-indenting a whole file is formatting-only; changing a space inside text, or inside a CDATA section, is a change; an attribute value's spacing is part of its token and compares exactly.
- **Whitespace-only text:** a tag whose whole text is whitespace, such as `<version>   </version>`, compares as layout too, so emptying it is formatting-only. Telling meaningful whitespace-only text from indentation would need a DTD or schema, which is out of scope; the rule keeps the common case, re-indenting, formatting-only (ADR 0003). A fixture records it.

## 7. Prolog, DOCTYPE and processing instructions (R14, R15)

- The XML declaration (`<?xml …?>`) and the DOCTYPE are in the prolog, outside the root tag, so they are file-region content; their tokens compare as content, and an encoding or version change is a change. A DOCTYPE's internal subset compares as written; no DTD validation or entity expansion runs.
- Processing instructions and comments outside the root tag are file-region content; inside a tag they are its content (R7). A comment change is a content change wherever it is (R14).

## 8. Parse errors (R17)

Parse errors follow technical specification §6.7. A file with errors and no declarations while non-blank is `FAILED`; an error inside a declaration's range makes that declaration `UNANALYZED` unless its own tokens are unchanged. The XML parser reports error elements for malformed markup; S10 records which inputs give error elements, including an unclosed tag, a mismatched end tag and a second root tag.

## 9. Renames, moves and relationships (R11, R12, R13, R16)

- **Rename candidates (R13):** a removed and an added `TAG` with similar content in the same container can pair as a rename candidate by the engine's rename rule (M5). The identifying value is masked through `nameRange` (section 4), and a tag-name rename masks the tokens whose text equals the name, so a tag renamed with its content unchanged keeps its content unchanged.
- **Moves (R11):** a reorder of child tags is reported as moved by the engine's order rule; a tag moved into another container is moved by container.
- **Repeated keys (R12):** as in section 5.
- **Extraction and inlining (R16):** tags have no bodies (`DeclarationInfo.body = null`), so neither applies. Kind changes (M4b) do not pair a `TAG` with a code kind, since `KEY`, `TAG` and `SECTION` are in no kind family.

## 10. Limits (R18)

XML files follow the shared [analysis limits](../../GLOSSARY.md#analysis-limits) ([engines/03](../engines/03-analysis-limits.md), technical specification §9.3): 1 MB per side, 5 000 declarations per side and 5 s. A very large XML file, such as a generated configuration or a large SVG, is `TOO_LARGE` and stays in the native diff, as any other file is; there is no XML-specific limit, consistent with ADR 0009's consequence for large data files.

## 11. Merges and the Changes tree (R19)

The merge outline and the Changes tree use the engine's result and need no XML-specific code. Merge fixtures are in `src/test/testData/xml/merge/` and cover the cases of [merge/05](../merge/05-scala-merges.md) R11, plus one tag changed on both sides.

## 12. Engine, corpus and the fallback (R20)

- `DeclarationEngine.VERSION` is raised, since a new provider can change results.
- `CorpusValidationTest` accepts `.xml` files (language id `XML`), so a later corpus run can include them; no corpus run is required now.
- The [structure-view fallback](../../GLOSSARY.md#structure-view-fallback) ([languages/06](06-structure-view-fallback.md)) no longer applies to XML files, since the provider registry picks the dedicated provider first (R20). A later corpus run is compared with the fallback's to record what the dedicated provider gained (plan §5).

## 13. Phases

| Phase | Content | Done when |
| --- | --- | --- |
| D4 (XML, part 1) | XML provider, spike S10 (XML part), fixtures and tests | R1 to R21 pass in tests named after them, and `./gradlew buildPlugin check` and `./gradlew verifyPlugin` pass |

D4b (HTML on the same provider) is the later part of format coverage D4 and is out of scope here.

## 14. Tests

- **Spike S10 (XML part):** a platform test builds XML files from text and checks that for every declaration the PSI element's text equals the request text sliced by its range, with the cases of section 2.
- **`XmlDeclarationProviderTest`:** one test per mapping rule (R3 to R6, R12); offsets equal the request text for every declaration.
- **Golden fixtures** in `src/test/testData/xml/`: attribute value changed (tag changed), attributes reordered (changed, F4), tag added, tag removed, child tags reordered (moved), named siblings reordered (moved), tag moved into another container, bean `id` renamed with the same content (rename candidate), tag name renamed (rename candidate), POM version bump (one changed dependency), POM dependency added and removed, POM coordinates renamed, repeated unnamed sibling tags, text content changed, text spacing changed (changed), whitespace-only text emptied (formatting-only), CDATA changed, whole file re-indented (formatting-only), spacing between attributes (formatting-only), comment changed, XML declaration encoding changed (file region changed), DOCTYPE changed, processing instruction changed, namespace prefix changed, parse error on one side, empty and identical files.
- **Merge fixtures** in `src/test/testData/xml/merge/`, as in section 11.
- **Rename cases:** a bean `id` rename, a tag-name rename and a POM coordinate rename, as golden fixtures; the relationship benchmark has no data-format cases yet, as for JSON, so they are not added there.
- **Corpus validation:** `CorpusValidationTest` accepts `.xml` for a later run over real commits, as for the other data formats.
- **Requirement names:** every test names its requirement, such as `// languages/13-xml R8`.
