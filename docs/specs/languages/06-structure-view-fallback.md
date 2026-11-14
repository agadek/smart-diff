# Structure-view fallback

Status: implemented, 2026-11-13

**Description:** A generic [declaration](../../GLOSSARY.md#declaration) provider builds the [structural outline](../../GLOSSARY.md#structural-outline) from the IDE's structure view for a file whose language has no dedicated provider, while the setting is on.

**Use case:** A reviewer opens a diff of a YAML or TypeScript file in an IDE that can parse it but for which SMART Diff has no dedicated provider. They still see which items in the structure view changed, instead of only the [native diff](../../GLOSSARY.md#native-diff).

## Requirements

- **R1** The [structure-view fallback](../../GLOSSARY.md#structure-view-fallback) analyzes a file whose language has no dedicated provider and has a structure view, while the setting is on.
- **R2** A dedicated provider always wins over the structure-view fallback for its language.
- **R3** Each structure-view item backed by a PSI element of the same file is a declaration; its children are its members; inherited or grouped items from other files are left out.
- **R4** Its name is the item's presentable text; two items with the same range are one declaration.
- **R5** Declarations from the structure-view fallback have no [signature](../../GLOSSARY.md#signature).
- **R6** Kinds map from the PSI class simple name (contains `Class` → `CLASS`, `Interface` → `INTERFACE`, `Function` → `FUNCTION`, `Method` → `METHOD`, `Field` → `FIELD`, `Property` → `PROPERTY`); everything else is the new kind `ITEM`, shown with no [kind](../../GLOSSARY.md#kind) word.
- **R7** Whitespace is [significant](../../GLOSSARY.md#significant-whitespace): the structure-view fallback never reports [formatting-only](../../GLOSSARY.md#formatting-only).
- **R8** The [summary line](../../GLOSSARY.md#summary-line) ends with `· structure view`, and the outline's tooltip and empty-state text say the outline comes from the IDE's structure view.
- **R9** A structure view that throws, needs an editor, or exceeds the [analysis limits](../../GLOSSARY.md#analysis-limits) gives a [FAILED](../outline/05-analysis-fallback.md) result and the native diff.
- **R10** The setting *Use the IDE's structure view for other languages* in Settings | Tools | SMART Diff is on by default.
- **R11** [Changes tree](../../GLOSSARY.md#changes-tree) summaries and the [merge outline](../../GLOSSARY.md#merge-outline) work for structure-view fallback files as for others.
- **R12** The plugin still loads and behaves as before in an IDE where a language has no structure view.

## Out of scope

- Signatures, a whitespace rule or reliable kinds for fallback declarations.
- Fallback analysis for plain text and other languages without a structure view.
- Building or testing against language plugins that are not bundled with IntelliJ IDEA Community.

## References

- [Details: design and tests](06-structure-view-fallback-details.md)
- [ADR 0010: A structure-view provider for languages without their own](../../adr/0010-structure-view-provider-for-languages-without-their-own.md)
- [Format coverage plan §3](../../FORMAT-COVERAGE.md#3-generic-fallback-group-d)
- [Settings page](../settings/01-settings-page.md)
