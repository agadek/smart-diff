# SMART Diff viewer

Status: implemented, 2026-10-22

**Description:** The [SMART Diff viewer](../../GLOSSARY.md#smart-diff-viewer) is a side-by-side viewer that fades the changes the [change type filter](../../GLOSSARY.md#change-type-filter) hides.

**Use case:** A reviewer hides formatting-only changes in the outline. In the SMART Diff viewer, the reformatted lines fade too, and next and previous change skip them.

## Requirements

- **R1** "Side-by-side viewer (SMART Diff)" is offered in the diff's viewer list.
- **R2** The native viewers keep their order, so the native side-by-side viewer stays the default.
- **R3** The IDE remembers the chosen viewer for each kind of diff.
- **R4** The viewer works as the native side-by-side viewer, except for faded changes.
- **R5** A change is faded when all its lines on both sides belong to changes the filter hides.
- **R6** A faded change has no fill and no gutter actions.
- **R7** Next and previous change skip faded changes.
- **R8** Lines of shown changes and of [unanalyzed](../../GLOSSARY.md#unanalyzed) source are never faded.
- **R9** Nothing is faded while an edit is compared again, or while the outline is off.

## References

- [Technical specification §10](../../TECHNICAL-SPECIFICATION.md#10-structural-outline)
