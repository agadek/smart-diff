# Merge outline in three-sided diffs

Status: proposed, 2026-10-31

**Description:** Shows the [merge outline](02-merge-outline.md) beside a [three-sided diff](../../GLOSSARY.md#three-sided-diff), with the middle pane as the [base](../../GLOSSARY.md#base).

**Use case:** A developer unshelves changes that conflict with their working copy and opens the three-sided diff. The outline shows which declarations the shelf and the working copy each changed.

## Requirements

- **R1** The outline attaches to a three-sided text diff whose three contents are in one language with an engine.
- **R2** The middle pane is the base, and the left and right panes are the [sides](../../GLOSSARY.md#side).
- **R3** The native three-sided diff keeps working as usual.
- **R4** When the outline cannot attach, the native diff shows alone.
- **R5** Selecting a row scrolls all three panes to the declaration.
- **R6** Synchronized scrolling pauses while the panes scroll, and the next scroll by the user syncs them again.
- **R7** Each side is named after the title its caller gives the pane.
- **R8** Three-sided diffs never show [merged without conflict](../../GLOSSARY.md#merged-without-conflict), since they have no conflicts.

## References

- [Three-way merge plan §6.1](../../THREE-WAY-MERGE.md#61-three-sided-diffs)
- [ADR 0007](../../adr/0007-merge-outline-attaches-through-a-delegating-merge-tool.md)
