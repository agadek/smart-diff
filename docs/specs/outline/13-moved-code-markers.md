# Moved-code markers

Status: proposed, 2026-11-16

**Description:** While the [structural outline](../../GLOSSARY.md#structural-outline) shows a result, each [moved](../../GLOSSARY.md#moved) [declaration](../../GLOSSARY.md#declaration) it lists gets a [moved-code marker](../../GLOSSARY.md#moved-code-marker) in both editors: a gutter icon on its first line that names the line on the other side, and a mark on the scrollbar. Clicking the icon selects the declaration in the outline, so both positions are framed without searching for the row first.

**Use case:** A reviewer reads a diff where a teammate reordered a class and changed one of the moved methods. Scrolling the new side, they see the moved icon next to `applyDiscount`, hover it to read `Moved from line 42`, and click it to frame the old and new positions side by side, instead of finding the method's row in the outline.

## Requirements

- **R1** "Mark moved code in the editors" is on by default, and with it off the editors are the same as without this feature.
- **R2** While the outline shows a result in a two-sided diff, each declaration the shown view lists with the `moved` label or as a move has one marker on each side, at the first line of its range there.
- **R3** A declaration that the [change type filter](../../GLOSSARY.md#change-type-filter) or the [relationship kind filter](../../GLOSSARY.md#relationship-kind-filter) hides has no marker.
- **R4** The marker's icon is the same on both sides and is shown in the gutter next to the line numbers, without changing the diff's own colours or the line text.
- **R5** The icon's tooltip names the declaration and the line on the other side, as `Moved from line N` on the [new side](../../GLOSSARY.md#new-side) and `Moved to line N` on the [old side](../../GLOSSARY.md#old-side), with N counted from 1.
- **R6** Each marker also adds a mark on that editor's scrollbar at the declaration's lines, in one colour for all moved-code markers.
- **R7** Clicking a marker's icon selects the declaration's row in the outline, which frames it on both sides and scrolls the other editor to it as selecting the row does.
- **R8** Clicking a marker while the outline is minimised shows the full panel and selects the row.
- **R9** Hiding the outline removes every marker, and showing it again adds them back with the result.
- **R10** An edit to either side removes the markers until the new result is shown, and the new result's markers replace them.
- **R11** A fallback state, `Comparing…` and an engine without a result show no markers.
- **R12** Changing the filter, the outline view or the setting updates the markers of open diffs without comparing the file again.
- **R13** The setting is a checkbox on the settings page, and changing it there applies to open diffs when applied.

## Out of scope

- The merge outline and [three-sided diffs](../../GLOSSARY.md#three-sided-diff).
- Lines drawn between the two editors, and a minimap.
- Moved code inside a declaration, which [Moves inside declarations](../../ROADMAP.md#moves-inside-declarations) covers.
- Markers for renames, extractions and inlinings that are not moves.
- The Changes tree.

## References

- [Details: design and tests](13-moved-code-markers-details.md)
- [Structural outline](01-structural-outline.md)
- [Change type filter](03-change-type-filter.md)
- [Refactorings view](../relationships/06-refactorings-view.md)
- [Settings page](../settings/01-settings-page.md) R20
- [Roadmap: Visual links for moved code](../../ROADMAP.md#visual-links-for-moved-code)
