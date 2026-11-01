# Changes tree coverage and scale

Status: implemented, 2026-11-01

**Description:** The [Changes tree](../../GLOSSARY.md#changes-tree) summarizes at most 500 files in one tree, in tree order, and leaves every other file as the platform draws it.

**Use case:** A developer opens Local Changes on a branch with more than a thousand changed files. They see summaries on the first files in the tree and ordinary rows on the rest, so the view stays responsive and no file disappears or changes its row type.

## Requirements

- **R1** At most 500 file nodes of one Changes tree are considered for a [summary line](../../GLOSSARY.md#summary-line).
- **R2** The considered nodes are the first 500 file nodes in the tree's depth-first order.
- **R3** A file node after the first 500 keeps the platform's row, with no summary and no declaration rows.
- **R4** A rebuild of the same tree summarizes the same 500 files, unless the tree's order changes.
- **R5** The 500 limit is intended behavior, not a setting, and it does not change the [analysis limits](../../GLOSSARY.md#analysis-limits).
- **R6** Files added or deleted in the change list get no summary in 0.2.0.
- **R7** A modified or moved file that is not analyzed shows its reason in place of the summary, as [outline/05](../outline/05-analysis-fallback.md) specifies.
- **R8** The summary is computed in the background, one file at a time, so a large tree stays responsive.
- **R9** A file left out by the limit is still selectable, includable in a commit and openable in the native diff.

## Out of scope

- Summaries for added and deleted files. This is later work in the [roadmap](../../ROADMAP.md#summaries-for-added-and-deleted-files).
- A setting for the 500-file limit.
- Summaries in the modal commit dialog and the Git log's commit details, which have no hook.

## References

- [Details: design and tests](03-coverage-and-scale-details.md)
- [Changes tree summary](01-changes-tree-summary.md)
- [Technical specification §9.4](../../TECHNICAL-SPECIFICATION.md#94-changes-tree-summary)
- [ADR 0006](../../adr/0006-changes-tree-summary-through-changes-view-modifier.md)
