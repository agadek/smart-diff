# HTML

Status: accepted, 2026-11-16

**Description:** The [declaration engine](../../GLOSSARY.md#declaration-engine) supports HTML and XHTML files through the platform's XML support, the same parser family as the [XML provider](13-xml.md).

**Use case:** A developer reviews a change to an HTML page or template and sees which identified sections, the `head`, the `body`, templates, scripts and styles changed, were added, removed, moved or renamed, without reading every changed line of markup.

## Requirements

- **R1** HTML support works through the platform's XML module (`com.intellij.modules.xml`), and the plugin loads without it.
- **R2** `.html` and `.htm` files, `.xhtml` files, and every file the IDE treats as HTML or XHTML on IntelliJ Platform 2025.1.3, are [supported languages](../../GLOSSARY.md#supported-language); template languages built on HTML (Vue, JSP, Thymeleaf and the like) are not.
- **R3** An element with a non-empty `id` attribute is a [declaration](../../GLOSSARY.md#declaration) of [kind](../../GLOSSARY.md#kind) `TAG`, named by its tag name and that id, such as `section id=pricing`.
- **R4** `head`, `body`, `template`, `script` and `style` elements are `TAG` declarations even without an `id`; a `script` with a `src` attribute and no `id` is named by its tag name and that source, such as `script src=app.js`, and the others by their tag name.
- **R5** Tag names in declaration names are lower case, since HTML tag and attribute names are case-insensitive; attribute values keep their case.
- **R6** Every other element is not a declaration: its tags, attributes and text are content of the nearest enclosing declaration, or of the [file region](../../GLOSSARY.md#file-region) when there is none.
- **R7** A declaration's members are the nearest declaration elements inside it, at any depth; the top-level declarations are the declaration elements inside no other declaration element.
- **R8** Attributes are content of their element: a changed, added, removed or reordered attribute changes the enclosing declaration.
- **R9** Embedded JavaScript and CSS are content of their `script` or `style` tag and are compared exactly, whitespace included, except line-break whitespace at their edges.
- **R10** Text content outside `script` and `style` is compared as XML text content is ([languages/13](13-xml.md) R9); `pre` and `textarea` content is compared exactly, whitespace included.
- **R11** Whitespace between tags and between attributes is [formatting-only](../../GLOSSARY.md#formatting-only) when nothing else changed.
- **R12** A reorder of declarations in one container is reported as [moved](../../GLOSSARY.md#moved).
- **R13** A declaration that appears twice in one container under one name follows the matcher's rules for repeated [keys](../../GLOSSARY.md#key).
- **R14** A declaration whose id changes with its content unchanged is a [rename candidate](../../GLOSSARY.md#rename-candidate).
- **R15** Comments are changes.
- **R16** The DOCTYPE, and comments and markup outside every declaration, are content of the file region.
- **R17** [Extraction](../../GLOSSARY.md#extraction) and inlining detection do not apply to HTML.
- **R18** [Parse errors](../../GLOSSARY.md#parse-error) on one side behave as for XML.
- **R19** An HTML file over the [analysis limits](../../GLOSSARY.md#analysis-limits) is not analyzed, as for other files.
- **R20** The merge outline and the Changes tree show HTML files as they show XML files.
- **R21** The HTML provider wins over the [structure-view fallback](../../GLOSSARY.md#structure-view-fallback) for HTML and XHTML files, and the XML provider's behaviour for XML files is unchanged.
- **R22** HTML has golden fixtures and merge fixtures.

## Out of scope

- Declarations inside embedded JavaScript or CSS, such as functions or rules: that needs the JavaScript and CSS providers (B1, B3).
- Template languages built on HTML, and HTML injected into strings of other languages.
- Every element as a declaration, or naming by `class`, `name` or other attributes.
- Rendering-aware comparison, such as collapsing whitespace in text as a browser does, or knowing that attribute order and tag name case carry no meaning.
- `link`, `meta` and other `head` elements as declarations; they stay content of `head`.

## References

- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0009](../../adr/0009-structured-data-keys-are-declarations.md)
- [Format coverage plan §2.1](../../FORMAT-COVERAGE.md#21-group-a-free-psi-on-the-current-build-target)
- [Technical specification §6.21](../../TECHNICAL-SPECIFICATION.md#621-html-provider)
- [XML](13-xml.md)
- [HTML details](15-html-details.md)
