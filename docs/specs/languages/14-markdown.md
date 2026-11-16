# Markdown

Status: implemented, 2026-11-16

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports Markdown files through the IDE's Markdown parser, when the IDE has Markdown support. Headings are [sections](../../GLOSSARY.md#section), nested by level.

**Use case:** A developer reviews a change to a README, a design document or a changelog and sees which sections changed, were added, removed, moved or renamed, without reading every changed line.

## Requirements

- **R1** Markdown support works when the IDE has Markdown support (`org.intellij.plugins.markdown`), and the plugin loads without it.
- **R2** `.md` and `.markdown` files, and every file the IDE treats as Markdown on IntelliJ Platform 2025.1.3, are [supported languages](../../GLOSSARY.md#supported-language).
- **R3** Each ATX heading (`# Title`) and setext heading (`Title` underlined with `=` or `-`) at the top level of the document is a [declaration](../../GLOSSARY.md#declaration) of [kind](../../GLOSSARY.md#kind) `SECTION`.
- **R4** A section is named by its heading text, trimmed, without the heading markers and without a closing run of `#`; inline markup stays as written.
- **R5** A heading with no text is named `(empty heading)`.
- **R6** A section runs from its heading to the next heading of the same level or a level with fewer `#`, or to the end of the file.
- **R7** A section's members are the sections of a lower level inside it, so a `###` section is a member of the `##` section above it.
- **R8** A heading whose level skips a level, such as `###` right under `#`, is a member of the nearest heading above it with fewer `#`.
- **R9** Headings inside a code block, a block quote, a list or an HTML block are not sections.
- **R10** Text before the first heading is part of the file region.
- **R11** A front matter block at the start of the file (`---` … `---` or `+++` … `+++`) is a `SECTION` named `front matter`, when the IDE parses it as front matter.
- **R12** A change in a section's text outside its member sections changes the section.
- **R13** A change in a heading's level that moves the section to another container is reported as [moved](../../GLOSSARY.md#moved).
- **R14** A reorder of sections in one container is reported as moved.
- **R15** A section renamed with its content unchanged is a [rename candidate](../../GLOSSARY.md#rename-candidate).
- **R16** Two sections with the same name in one container follow the matcher's rules for repeated keys.
- **R17** No change in a Markdown file is [formatting-only](../../GLOSSARY.md#formatting-only).
- **R18** [Extraction](../../GLOSSARY.md#extraction) and inlining detection do not apply to Markdown.
- **R19** The merge outline and the Changes tree show Markdown files as they show JSON files.
- **R20** Markdown has golden fixtures and merge fixtures.

## Out of scope

- Declarations below the heading level, such as list items, tables, code blocks or link definitions.
- The keys of a front matter block, which stay content of its section.
- Formatting-only detection for re-wrapped paragraphs or changed list markers.
- Markdown dialects with their own file types, such as MDX or R Markdown, unless the IDE treats them as Markdown.

## References

- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0009](../../adr/0009-structured-data-keys-are-declarations.md)
- [Format coverage plan §2.1](../../FORMAT-COVERAGE.md#21-group-a-free-psi-on-the-current-build-target)
- [Technical specification §6.20](../../TECHNICAL-SPECIFICATION.md#620-markdown-provider)
- [Markdown details](14-markdown-details.md)
