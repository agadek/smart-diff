# HTML: details

Status: implemented, 2026-11-16

**Feature:** [HTML](15-html.md)

## 1. Packaging and IDE versions

- **Module:** HTML PSI is platform XML support, module `com.intellij.modules.xml` (R1), present in every IntelliJ-based IDE on the current target, as for [XML](13-xml-details.md#1-packaging-and-ide-versions).
- **Descriptor:** the provider registers in the existing `META-INF/smartdiff-xml.xml`, after the XML provider. No new `<depends>` line is needed.
- **Build:** no change to `build.gradle.kts`.
- **IDE versions:** no change to P6.

## 2. Language ids and file types

The platform provides the `HTML` language (`HTMLLanguage`) for `.html` and `.htm` files and the `XHTML` language (`XHTMLLanguage`) for `.xhtml` files (R2). The provider's `supports` covers exactly these two ids. `XHTMLLanguage` extends `XMLLanguage`, but its id is `XHTML`, so the XML provider, which supports exactly `XML`, keeps ignoring it (R21). Languages that extend `HTMLLanguage` with their own id, such as Vue or JSP, are left out, since their PSI adds template constructs this mapping does not cover. Spike S10 (HTML part) confirms both ids on IntelliJ Platform 2025.1.3 and that text-built files give correct element ranges, including a DOCTYPE, void elements (`<br>`, `<img>`), omitted end tags (`<li>`, `<p>`), unquoted and valueless attributes, upper-case tag names, `script` and `style` content, comments and CRLF line ends. XHTML uses the XML parser, so its attribute values need quotes to become `XmlAttribute`s and identify elements.

## 3. PSI mapping

The provider is an `HtmlDeclarationProvider` extending `PsiDeclarationProvider`, in `engine/declarations/psi/xml/`, beside `XmlDeclarationProvider`. The two share the whitespace logic of section 6 through one internal helper in that package, so XML behaviour is unchanged (R21).

| PSI element | Declaration | Kind | Name | Members |
| --- | --- | --- | --- | --- |
| `HtmlFile` (an `XmlFile`) | none | — | — | the top-level declaration elements (R7) |
| `XmlTag` that is a declaration element (section 4) | one declaration | `TAG` | the naming rules of section 4 (R3, R4, R5) | the nearest declaration elements inside it (R7) |
| `XmlTag` that is not a declaration element | none | — | — | content of the nearest enclosing declaration, or of the file region (R6) |
| `XmlAttribute`, `XmlText`, `XmlComment`, embedded script and style content | none | — | — | content of the enclosing declaration, or of the file region (R6, R8, R9, R15) |
| `XmlDoctype`, and everything outside every declaration element | none | — | — | the file region (R16) |

- **Declaration elements:** an `XmlTag` is a declaration element when it has a non-empty `id` attribute, or when its lower-case tag name is `head`, `body`, `template`, `script` or `style` (R3, R4).
- **Top level (R7):** `topLevel` returns the declaration elements of the file that have no declaration element among their ancestors, in document order. The `html` element is not a declaration element unless it has an `id`, so a usual page has `head` and `body` at the top level, and its `html` start tag, its attributes such as `lang`, and the DOCTYPE are file-region content. A fragment without `html`, `head` or `body`, such as a partial template, has its identified elements, templates, scripts and styles at the top level.
- **Members (R7):** a declaration's members are found by walking its descendant tags and stopping at each declaration element: that element is a member, and its own descendants belong to it. So `body > main > section id=pricing` makes `section id=pricing` a member of `body`, and the `main` start and end tags are content of `body`.
- **Ranges:** a declaration's range is exactly the `XmlTag`'s text range. For an element whose end tag is omitted, it is the range the parser gives the tag; S10 records it. Offsets equal the request text sliced by the range, including with CRLF line ends.
- **Templates:** a `template` element's children are parsed as ordinary tags, so identified elements inside a template are its members.
- **No body, no signature:** `DeclarationInfo.body` is null and `DeclarationInfo.signature` is null (R17).

## 4. Naming (R3, R4, R5)

A declaration element is named by the first rule that applies:

1. **`id` attribute (R3):** the element has an `id` attribute, matched without regard to case, with a non-empty value. The name is `<tag> id=<value>`, such as `div id=main` or `template id=row`.
2. **Script source (R4):** the element is a `script` with a `src` attribute with a non-empty value. The name is `script src=<value>`, such as `script src=/js/app.js`.
3. **Tag name (R4):** otherwise the lower-case tag name: `head`, `body`, `template`, `script` or `style`.

- **Case (R5):** the tag name is lower-cased with `Locale.ROOT`, so `<BODY>` and `<body>` are the same declaration, and a change of case in a tag name is a content change of the enclosing declaration, not a rename. Attribute values keep their case, since ids are case-sensitive in HTML.
- **Values:** the attribute value without its quotes, as written, quoted or unquoted; character references are not resolved. An empty or valueless attribute (`<div id>`) does not name the element, and the next rule applies; a `div` with an empty id is then not a declaration element.
- **`nameRange`:** for rules 1 and 2, the identifying attribute's value element's text range, as for XML, so an id or source change with the same content leaves the content unchanged (R14). For rule 3, null: the engine masks tokens whose text equals the name; an upper-case tag name is not masked, which only matters for a renamed `head`, `body` or similar, which is not a rename case.
- **Repeated names (R13):** several `script` or `style` elements without an id or source in one container, or two elements with one id, repeat a key and follow the matcher's rules for repeated keys (M1/M4). Elements are never matched by position.

## 5. Content, attributes and order (R6, R8, R12)

- **Attributes (R8):** attributes are tokens of the enclosing declaration; a reordered attribute changes it, as for XML (F4). The attributes of a declaration element itself are its own content.
- **Non-declaration markup (R6):** the tags, attributes and text of elements that are not declarations are tokens of the nearest enclosing declaration. A change to a paragraph inside `section id=pricing` changes `section id=pricing`; a change to a paragraph directly inside `body` changes `body`.
- **Order (R12):** declarations are members, so the engine's order rule reports a reorder of sibling declarations as moved, and a declaration moved into another container as moved by container. A reorder of non-declaration elements is a content change of their enclosing declaration.

## 6. Whitespace and text (R9, R10, R11, R15, R16)

- **Policy:** HTML and XHTML files use `WhitespacePolicy.INSIGNIFICANT`. `OutlineController.whitespacePolicy` maps the `HTML` and `XHTML` language ids to `INSIGNIFICANT` (R11).
- **Whitespace token types:** as for XML ([languages/13 details §6](13-xml-details.md#6-whitespace-and-text-r7-r9-r10-r14-r15)), only the XML lexer's whitespace types and the platform's generic `WHITE_SPACE` are layout; a blank attribute value or the characters of a comment compare exactly (R8, R15). S10 records the types the HTML lexer emits, including inside `script` and `style`.
- **Exact ranges:** `significantWhitespace(file)` returns:
  - The XML provider's ranges: each non-blank `XmlText` with its line-break-free edge whitespace (R10), each CDATA section, processing instruction and DOCTYPE, whole (R16).
  - The content of each `script` and `style` element, from the end of its start tag to the start of its end tag, without leading and trailing whitespace that holds a line break (R9). Embedded JavaScript and CSS can depend on line breaks and spacing, so they compare exactly whatever PSI the IDE builds for them: plain text in IntelliJ IDEA without JavaScript support, or JavaScript and CSS PSI in IDEs with it (ADR 0003). The platform's HTML lexer emits the whole content as one token, so its edge whitespace compares exactly too; trimming the range only matters when the lexer emits edge whitespace as separate tokens.
  - The content of each `pre` and `textarea` element, whole, from the end of its start tag to the start of its end tag (R10), since whitespace there is rendered.
- **Consequences:** re-indenting markup is formatting-only, as is spacing between tags and between attributes. Re-indenting a `script` or `style` block is a change of that script or style, since its inner lines move; the outline shows it as changed and the native diff shows the indentation. Reflowing a paragraph's text over different line breaks is a change, as for XML text (R10).

## 7. DOCTYPE, comments and the file region (R15, R16)

- The DOCTYPE is file-region content, compared exactly (R16).
- A comment inside a declaration is its content; a comment outside every declaration is file-region content. A comment change is a change wherever it is (R15). Conditional comments are comments.

## 8. Parse errors (R18)

Parse errors follow technical specification §6.7, as for XML. The HTML parser is lenient: omitted end tags, void elements and unquoted attributes are valid and give no error elements. S10 records which inputs give error elements, including a stray end tag and an unclosed attribute quote; a file with errors and no declarations while non-blank is `FAILED`, and an error inside a declaration's range makes that declaration `UNANALYZED` unless its own tokens are unchanged.

## 9. Renames, moves and relationships (R12, R13, R14, R17)

- **Rename candidates (R14):** a removed and an added `TAG` with similar content in the same container can pair as a rename candidate by the engine's rename rule (M5). The id or source value is masked through `nameRange`, so an element whose id changes with its content unchanged keeps its content unchanged.
- **Moves (R12):** as in section 5.
- **Extraction and inlining (R17):** tags have no bodies, so neither applies; `TAG` is in no kind family, so kind changes never pair it with a code kind.

## 10. Limits (R19)

HTML files follow the shared [analysis limits](../../GLOSSARY.md#analysis-limits) ([engines/03](../engines/03-analysis-limits.md)): 1 MB per side, 5 000 declarations per side and 5 s. A large generated page stays in the native diff, as any other file does.

## 11. Merges and the Changes tree (R20)

The merge outline and the Changes tree use the engine's result and need no HTML-specific code. Merge fixtures are in `src/test/testData/html/merge/` and cover the cases of [merge/05](../merge/05-scala-merges.md) R11, plus one declaration changed on both sides.

## 12. Engine, corpus and the fallback (R21)

- `DeclarationEngine.VERSION` is raised, since a new provider can change results.
- `CorpusValidationTest` accepts `.html`, `.htm` and `.xhtml` files (language ids `HTML` and `XHTML`), so a later corpus run can include them; no corpus run is required now.
- The structure-view fallback no longer applies to HTML and XHTML files, since the provider registry picks the dedicated provider first (R21).
- The XML provider's tests and fixtures pass unchanged (R21).

## 13. Phases

| Phase | Content | Done when |
| --- | --- | --- |
| D4b | HTML provider, spike S10 (HTML part), fixtures and tests | R1 to R22 pass in tests named after them, and `./gradlew buildPlugin check` and `./gradlew verifyPlugin` pass |

## 14. Tests

- **Spike S10 (HTML part):** a platform test builds HTML and XHTML files from text and checks that for every declaration the PSI element's text equals the request text sliced by its range, with the cases of section 2.
- **`HtmlDeclarationProviderTest`:** one test per mapping and naming rule (R3 to R7, R13), including nested members through non-declaration elements, a fragment without `body`, upper-case tag names, unquoted ids, an empty id, and `script src`; offsets equal the request text for every declaration.
- **Golden fixtures** in `src/test/testData/html/`: paragraph text changed inside an identified section (that section changed), text changed directly in `body` (`body` changed), `html lang` changed (file region changed), DOCTYPE changed, attribute value changed, attributes reordered (changed), identified section added, removed, reordered (moved) and moved into another container, id renamed with the same content (rename candidate), script content changed, script re-indented (changed), script `src` changed (rename candidate), two unnamed inline scripts (repeated keys), style content changed, `pre` spacing changed (changed), whole file re-indented outside scripts and styles (formatting-only), spacing between attributes (formatting-only), tag name case changed (enclosing declaration changed), comment changed, an XHTML file, parse error on one side, empty and identical files.
- **Merge fixtures** in `src/test/testData/html/merge/`, as in section 11.
- **XML unchanged:** the existing XML tests and fixtures pass without edits (R21).
- **Requirement names:** every test names its requirement, such as `// languages/15-html R4`.
