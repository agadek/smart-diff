# Summaries for added and deleted files

Status: accepted, 2026-11-10

**Description:** The [Changes tree](../../GLOSSARY.md#changes-tree) gives an added file a [summary line](../../GLOSSARY.md#summary-line) for its added [declarations](../../GLOSSARY.md#declaration) and a deleted file for its [removed](../../GLOSSARY.md#removed) declarations, with the same declaration rows as a modified file.

**Use case:** Before committing, a developer adds a new class or deletes an old one and wants to see how many declarations it contains and open one without reading the whole file.

## Requirements

- **R1** An added file with a [supported language](../../GLOSSARY.md#supported-language), a [comparison engine](../../GLOSSARY.md#comparison-engine) and at least one reported change gets a summary line that counts its [added](../../GLOSSARY.md#added) declarations and a reported [file region](../../GLOSSARY.md#file-region).
- **R2** A deleted file with a supported language, a comparison engine and at least one reported change gets a summary line that counts its removed declarations and a reported file region.
- **R3** The file can be expanded to show its declaration rows.
- **R4** The rows have the same nesting, groups and [labels](../../GLOSSARY.md#label) as the file's [structural outline](../../GLOSSARY.md#structural-outline).
- **R5** The tree's "Structural Summary" and "Declarations" toggles and its [change type filter](../../GLOSSARY.md#change-type-filter) apply to these rows as they do to a modified file, including the hidden counts.
- **R6** Opening a declaration row opens the file's [one-sided diff](../../GLOSSARY.md#one-sided-diff) with that declaration framed on the side that exists.
- **R7** When the file's one-sided diff with the declaration selected cannot open, the row opens the file's diff without a declaration selected.
- **R8** The caret in a one-sided diff of an added or deleted file selects the declaration row under the caret, or the file row when the caret is outside every declaration; the platform's selection-following preview still selects the file row.
- **R9** A renamed or moved file is not an added or deleted file and keeps the summary and rows of a modified file.
- **R10** A binary or unsupported file gets no summary and no declaration rows.
- **R11** A supported file over an [analysis limit](../../GLOSSARY.md#analysis-limits) shows its reason in place of the summary and has no declaration rows.
- **R12** Added and deleted files count toward the 500-file limit in tree order.
- **R13** The summary and rows are computed in the background with the usual analysis limits, and no revision content is read on the UI thread.
- **R14** The summary is cached by the file's path, the side that exists and its version, the missing side, the comparison engine and the analysis settings, so an edit to an added file gives it a new summary.
- **R15** The summary appears in Local Changes and the Commit tool window.

## Out of scope

- Relationships for an added or deleted file. One side is empty, so it has no [extraction](../../GLOSSARY.md#extraction) or [inlining](../../GLOSSARY.md#inlining); the [refactorings view](../relationships/06-refactorings-view.md) is not part of this feature.
- Summaries in the modal commit dialog and the Git log's commit details, which have no hook.
- A setting for the 500-file limit.

## References

- [Changes tree summary](01-changes-tree-summary.md)
- [Declarations in the Changes tree](02-changes-tree-declarations.md)
- [Changes tree coverage and scale](03-coverage-and-scale.md)
- [Hide and filter in the Changes tree](04-hide-and-filter.md)
- [Changes tree caret follow](05-changes-tree-caret-follow.md)
- [Structural outline](../outline/01-structural-outline.md)
- [Details: design and tests](06-added-deleted-file-summaries-details.md)
- [Technical specification §9.4](../../TECHNICAL-SPECIFICATION.md#94-changes-tree-summary)
- [ADR 0006](../../adr/0006-changes-tree-summary-through-changes-view-modifier.md)
