# XML

Status: accepted, 2026-11-16

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports XML files, Maven POMs, Spring configuration and Android XML included, through the platform's XML parser.

**Use case:** A developer reviews a change to a `pom.xml`, a Spring configuration or another XML file and sees which [tags](../../GLOSSARY.md#tag) changed, were added, removed, moved or renamed, a dependency version bump as one changed dependency, without reading every changed line.

## Requirements

- **R1** XML support works through the platform's XML module (`com.intellij.modules.xml`), and the plugin loads without it.
- **R2** `.xml` files, `pom.xml`, Spring configuration and Android XML, and every file the IDE treats as XML on IntelliJ Platform 2025.1.3, are [supported languages](../../GLOSSARY.md#supported-language); HTML and XHTML files are not.
- **R3** Each tag is a [declaration](../../GLOSSARY.md#declaration) of [kind](../../GLOSSARY.md#kind) `TAG`, named by its tag name as written, prefix included.
- **R4** A tag with an `id` or `name` attribute is named by its tag name and that attribute, `id` first, such as `bean id=dataSource`.
- **R5** A Maven POM `dependency` or `plugin` tag with `groupId` and `artifactId` child tags is named by its tag name and `groupId:artifactId`, so a version bump is one changed dependency.
- **R6** The root tag is the top-level declaration, and a tag's child tags are its members.
- **R7** A tag's attributes, text, comments, CDATA and processing instructions are its content: changing them changes the tag.
- **R8** A reorder of a tag's attributes changes the tag.
- **R9** Text content, CDATA included, is compared exactly, whitespace included.
- **R10** Whitespace inside a tag outside text content is [formatting-only](../../GLOSSARY.md#formatting-only) when nothing else changed.
- **R11** A reorder of child tags is reported as [moved](../../GLOSSARY.md#moved).
- **R12** A tag that appears twice in one container under one name follows the matcher's rules for repeated [keys](../../GLOSSARY.md#key).
- **R13** A tag renamed with its content unchanged is a [rename candidate](../../GLOSSARY.md#rename-candidate).
- **R14** Comments are changes.
- **R15** The XML declaration, DOCTYPE and processing instructions outside the root tag are content of the [file region](../../GLOSSARY.md#file-region).
- **R16** [Extraction](../../GLOSSARY.md#extraction) and inlining detection do not apply to XML.
- **R17** [Parse errors](../../GLOSSARY.md#parse-error) on one side behave as for JSON: a file with errors and no declarations while non-blank is not analyzed, and an error inside a declaration's range makes that declaration unanalyzed unless its own tokens are unchanged.
- **R18** An XML file over the [analysis limits](../../GLOSSARY.md#analysis-limits) is not analyzed, as for other files.
- **R19** The merge outline and the Changes tree show XML files as they show JSON files.
- **R20** The XML provider wins over the [structure-view fallback](../../GLOSSARY.md#structure-view-fallback) for XML files.
- **R21** XML has golden fixtures and merge fixtures.

## Out of scope

- HTML on the same provider: that is D4b, the later part of format coverage D4, and is not specified here.
- Namespace-aware names: resolving a prefix to its namespace URI, or matching a tag across a prefix change.
- Resolving Maven properties (`${…}`), or coordinate names for other POM tags such as `parent` and `module`.
- Attribute-level declarations; attributes stay content of their tag (F4).
- Value-aware comparison, entity expansion, XInclude or schema validation.

## References

- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0009](../../adr/0009-structured-data-keys-are-declarations.md)
- [Format coverage plan §4](../../FORMAT-COVERAGE.md#4-structured-data-group-c)
- [Technical specification §6.19](../../TECHNICAL-SPECIFICATION.md#619-xml-provider)
- [XML details](13-xml-details.md)
