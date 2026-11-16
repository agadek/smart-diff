# Markdown: details

Status: implemented, 2026-11-16

**Feature:** [Markdown](14-markdown.md)

## 1. Packaging and IDE versions

- **Plugin:** Markdown PSI comes from the bundled Markdown plugin, `org.intellij.plugins.markdown` (R1). The optional descriptor is re-checked on IntelliJ Platform 2025.1.3 and the latest release.
- **Descriptor:** the provider registers in `META-INF/smartdiff-markdown.xml`, loaded through `<depends optional="true" config-file="smartdiff-markdown.xml">org.intellij.plugins.markdown</depends>` in `plugin.xml`, after the TOML line.
- **Build:** `bundledPlugin("org.intellij.plugins.markdown")` is added to `build.gradle.kts`, next to the other bundled format plugins. Plugin Verifier runs on the same IDEs as today.
- **IDE versions:** no change to P6.

## 2. Language id and file types

The Markdown plugin provides the `Markdown` language for `.md` and `.markdown` files, and for the other extensions the IDE maps to it (R2). The provider's `supports` covers that language id. Spike S10 (Markdown part) confirmed the id and that text-built files give correct ranges for ATX and setext headings, closing `#` runs, skipped levels, headings in code blocks, block quotes and lists, YAML and TOML front matter, and CRLF line ends.

## 3. PSI mapping

On 2025.1.3 the spike found no section wrappers: `MarkdownHeader`s are direct children of the `MarkdownFile`. The provider reads those children and also walks into a non-container wrapper, so the other shape is handled too, but it never descends into code blocks, block quotes, lists, tables or HTML blocks (R9). A heading below such a container is not a section.

| PSI element | Declaration | Kind | Name | Members |
| --- | --- | --- | --- | --- |
| `MarkdownFile` | none | — | — | front matter, then the top-level sections; text before the first heading is the file region (R10) |
| `MarkdownHeader` (ATX or setext) | one declaration | `SECTION` | heading text (R4, R5) | the sections nested under it (R7, R8) |
| Front matter header element | one declaration | `SECTION` | `front matter` (R11) | none |

- **Heading text (R4, R5):** the text of `MarkdownHeader.contentElement`, trimmed at both ends. On 2025.1.3 that element already excludes the ATX `#` markers, a closing run of `#` and the setext underline, while keeping inline markup such as `` `code` ``, `**bold**` and links as written; unlike `MarkdownHeader.name`, it does not strip that markup. An empty result is `(empty heading)`. The `nameRange` is the content element's text range, or the heading's range for an empty heading, so matching masks the name token. The spike recorded that a bare `#` has no content element and that a run of spaces before a closing `#` trims to empty.
- **Level:** 1 to 6 from the `#` count; setext `=` is level 1 and `-` is level 2.
- **Ranges (R6):** a section's range starts at its heading's first character and ends at the end of the last line before the next top-level heading with the same number of `#` or fewer, or at the end of the file. Trailing blank lines before that next heading belong to the section. Offsets equal the request text sliced by the range, including with CRLF line ends.
- **Nesting (R7, R8):** the headings are walked in document order with a stack of open sections. A heading closes every open section of the same or a deeper level, then becomes a member of the section left on top of the stack, or a top-level declaration if the stack is empty. So a skipped level nests under the nearest higher heading, and a member's range always lies inside its container's range.
- **Front matter (R11):** the spike found `MarkdownFrontMatterHeader` (element type `Markdown:FRONT_MATTER_HEADER`) at offset 0 for both `---` and `+++` fences on 2025.1.3. Its element text range is one `SECTION` named `front matter`, with no members, and the following heading never nests under it. If an IDE does not parse a block as front matter, the block stays ordinary text in the file region, or a setext heading if the parser reads it that way.
- **No body, no signature:** `DeclarationInfo.body` is null and `DeclarationInfo.signature` is null (R18).

## 4. Whitespace, comments and parse errors (R12, R17)

- **Whitespace (R17):** Markdown files use `WhitespacePolicy.SIGNIFICANT`. Spacing changes meaning in code blocks, list nesting and hard line breaks (two trailing spaces), and no formatting rule for Markdown has been tested ([ADR 0003](../../adr/0003-conservative-classification.md)), so nothing is formatting-only. `OutlineController.whitespacePolicy` maps the `Markdown` language id to it, or relies on the default if that is already `SIGNIFICANT`.
- **Tokens:** own tokens come from the Markdown lexer, as for every PSI provider (technical specification §6.3), so a change in a section's own text, outside its member sections, changes it (R12).
- **HTML comments** (`<!-- -->`) are compared as token text, like any other text.
- **Parse errors:** the Markdown parser accepts any text and reports no errors, so files are never `FAILED` for parse errors; technical specification §6.7 still applies if a future parser reports them.

## 5. Moves, renames and repeated names (R13 to R16)

- **Moves (R13, R14):** a reorder of sections in one container is moved by the engine's order rule. A heading whose level changes so that it nests under another section, or leaves one, is moved by container; when its own text also changed, it is moved and changed, as for any declaration.
- **Rename candidates (R15):** a removed and an added `SECTION` with similar content in the same container pair as a rename candidate by the engine's rename rule (M5), so a reworded heading over unchanged text reads as one renamed section.
- **Repeated names (R16):** names such as `Example` or `Notes` repeat under different parents and are matched within their container; two in one container follow the matcher's rules for repeated keys (M1/M4), never by position alone.
- **Extraction and inlining (R18):** sections have no bodies, so neither applies.

## 6. Merges and the Changes tree (R19)

The merge outline and the Changes tree use the engine's result and need no Markdown-specific code. Merge fixtures are in `src/test/testData/markdown/merge/` and cover the cases of [merge/05](../merge/05-scala-merges.md) R11, plus one section changed on both sides.

## 7. Engine and corpus

- `DeclarationEngine.VERSION` is raised, since a new provider can change results.
- `CorpusValidationTest` accepts `.md` files (language id `Markdown`), so a later corpus run can include them; no corpus run is required now.
- The structure-view fallback ([languages/06](06-structure-view-fallback.md)) no longer applies to Markdown files, since the provider registry picks the dedicated provider first. The S9 recorded case list drops its Markdown example and the Markdown provider test asserts that the same text now gives one `SECTION` with a nested section instead of the fallback's two `ITEM`s. The structure-view Markdown golden fixtures stay by masking the dedicated provider, as for JSON and YAML.

## 8. Phases

| Phase | Content | Done when |
| --- | --- | --- |
| M1 | Markdown provider, spike S10 (Markdown part), fixtures and tests | R1 to R20 pass in tests named after them, and `./gradlew buildPlugin check` and `./gradlew verifyPlugin` pass |

## 9. Tests

- **Spike S10 (Markdown part):** `MarkdownSpikeTest` builds files from text, prints and asserts the PSI shape recorded in sections 2 and 3, and checks that for every declaration the request text sliced by its range starts with its heading, and that member ranges lie inside container ranges, with the cases of section 2.
- **`MarkdownDeclarationProviderTest`:** one test per mapping rule (R3 to R11, R16); offsets equal the request text for every declaration. Its `assertS10` repeats the range and nesting checks through the registered provider.
- **Golden fixtures** in `src/test/testData/markdown/`: paragraph changed in a section, section added, section removed, sections reordered (moved), subsection moved to another parent, heading level changed (moved by container), heading reworded with the same text (rename candidate), setext heading, closing `#` run, skipped level, heading inside a fenced code block, heading inside a block quote and a list, front matter changed, text before the first heading changed (file region), duplicate heading names in one container and in different containers, trailing spaces added (changed, not formatting-only), empty heading, empty and identical files.
- **Merge fixtures** in `src/test/testData/markdown/merge/`, as in section 6.
- **Relationship benchmark:** rename cases only, as for JSON. The Markdown lexer emits paragraph-sized tokens, so the reworded-heading fixture keeps eight paragraphs of unchanged text: the matcher's rename rule needs at least six content tokens (§5).
- **Requirement names:** every test names its requirement, such as `// languages/14-markdown R8`.

## 10. Open questions

- **Front matter keys:** the front matter's keys could be `KEY` members parsed by the YAML or TOML provider; they stay content until a reviewer asks for them.
- **Prose formatting:** a re-wrapped paragraph could be formatting-only outside code blocks; this needs its own tested rule and stays a change.
