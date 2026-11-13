# Outline caret follow

Status: accepted, 2026-11-13

**Description:** When the caret moves into a changed [declaration](../../GLOSSARY.md#declaration) on either side of a
two-sided diff, the [structural outline](../../GLOSSARY.md#structural-outline) selects that declaration's row and frames it, without scrolling
the editors or taking focus.

**Use case:** A developer reads a diff in the editors rather than in the outline. They move the caret into another
changed method, and the outline marks that method, so the outline always says which change they are reading and the frame
does not stay on a method they have left.

## Requirements

- **R1** When the caret is inside a changed declaration, the outline selects the innermost shown row whose range on the
  caret's side contains the caret, and scrolls that row into view in the outline.
- **R2** On the [old side](../../GLOSSARY.md#old-side) the caret maps to the rows' old-side ranges, and on the
  [new side](../../GLOSSARY.md#new-side) to their new-side ranges.
- **R3** When the caret is outside every shown row's range, the outline keeps its selection.
- **R4** A row selected by following is framed on both sides as a clicked row is, but neither editor scrolls and focus
  stays in the editor.
- **R5** Following does not change the selection while the outline has focus, and caret moves are debounced so typing
  and arrow keys stay smooth.
- **R6** When a declaration has a row in the [refactorings view](../../GLOSSARY.md#refactorings-view) and a row under
  "Other changes", following selects its row under "Other changes", or the row in its container tree when the outline
  shows that view.
- **R7** Following uses only the rows that are shown, so a row hidden by the change type filter is never selected.
- **R8** While the outline's result is stale after an edit, following does not select a row from the old result, and it
  follows the caret when the new result is shown.
- **R9** Following uses no internal, experimental or deprecated platform API.

## Out of scope

- Three-way merges and three-sided diffs.
- A setting to turn following off.
- The Changes tree, which follows the caret through [changes-tree/05](../changes-tree/05-changes-tree-caret-follow.md).

## References

- [Details: design and tests](07-outline-caret-follow-details.md)
- [Structural outline](01-structural-outline.md)
- [Changes tree caret follow](../changes-tree/05-changes-tree-caret-follow.md)
