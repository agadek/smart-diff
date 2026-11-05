# Merge outline in three-sided diffs

Status: implemented, 2026-11-05

**Description:** Shows the [merge outline](02-merge-outline.md) beside a [three-sided diff](../../GLOSSARY.md#three-sided-diff), with the middle pane as the [base](../../GLOSSARY.md#base).

**Use case:** A developer unshelves changes that conflict with their working copy and opens the three-sided diff. The outline shows which declarations the shelf and the working copy each changed.

## Requirements

- **R1** The outline attaches to a three-sided text diff whose three contents are in one language with an engine.
- **R2** The middle pane is the base, and the left and right panes are the [sides](../../GLOSSARY.md#side).
- **R3** The native three-sided diff keeps working as usual.
- **R4** When the outline cannot attach, the native diff shows alone.
- **R5** Selecting a row scrolls all three panes to the declaration.
- **R6** While the selected row's ranges are in view, scrolling any pane keeps them aligned, scaled when the ranges have different lengths.
- **R7** Each side is named after the title its caller gives the pane.
- **R8** Three-sided diffs never show [merged without conflict](../../GLOSSARY.md#merged-without-conflict), since they have no conflicts.
- **R9** An edit to any pane compares the three versions again, 300 ms after typing stops.
- **R10** A result computed before an edit is never shown.
- **R11** When any selected range leaves view, the selection changes or clears, a document changes or the diff closes, synchronized scrolling resumes.

## References

- [Three-way merge plan §6.1](../../THREE-WAY-MERGE.md#61-three-sided-diffs)
- [ADR 0007](../../adr/0007-merge-outline-attaches-through-a-delegating-merge-tool.md)
