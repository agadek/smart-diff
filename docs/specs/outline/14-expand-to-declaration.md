# Expand to declaration

Status: proposed, 2026-11-16

**Description:** "Expand Declaration" opens every [collapsed unchanged fragment](../../GLOSSARY.md#collapsed-unchanged-fragment) of the [native diff](../../GLOSSARY.md#native-diff) that lies in a changed [declaration](../../GLOSSARY.md#declaration), on both sides, so the reviewer reads the whole declaration without opening each fragment by hand. Fragments outside the declaration stay collapsed.

**Use case:** A reviewer reads a diff with "Collapse unchanged fragments" on. One line changed in the middle of a long method, so the diff shows that line with a few lines around it and hides the rest of the method in two collapsed fragments. To judge the change they need the whole method. They choose "Expand Declaration" on the method's row in the [structural outline](../../GLOSSARY.md#structural-outline), or in the editor with the caret on the changed line, and both editors show the whole method while the rest of the file stays collapsed.

## Requirements

- **R1** The context menu of a change row in the two-sided outline has "Expand Declaration".
- **R2** The context menu of each editor of a two-sided diff with an outline result has "Expand Declaration", for the innermost changed row whose range on that side contains the caret.
- **R3** "Expand Declaration" expands every collapsed unchanged fragment that overlaps the row's range on the [old side](../../GLOSSARY.md#old-side) or the [new side](../../GLOSSARY.md#new-side).
- **R4** A fragment expands on both sides at once.
- **R5** A fragment that reaches past the declaration expands whole.
- **R6** A collapsed fragment that does not overlap the row's range on either side stays collapsed.
- **R7** For a [relationship](../../GLOSSARY.md#relationship) row in the [refactorings view](../../GLOSSARY.md#refactorings-view), "Expand Declaration" expands the fragments of every declaration the row frames.
- **R8** "Expand Declaration" is hidden for a group row, the [file region](../../GLOSSARY.md#file-region) row and a row with no collapsed fragment in its ranges, and in the editor menu when no row contains the caret.
- **R9** After expanding, each editor shows the declaration's first line on its side, and the outline keeps its selection.
- **R10** Expanding never compares the file again, never changes the outline, and leaves the diff's "Collapse unchanged fragments" setting as it was.

## Out of scope

- Expanding only the part of a fragment that lies in the declaration.
- Collapsing the fragments again; the diff's own collapse does that.
- Expanding automatically when a row is selected.
- The unified diff viewer, the merge outline and [three-sided diffs](../../GLOSSARY.md#three-sided-diff).
- [One-sided diffs](../../GLOSSARY.md#one-sided-diff), which have no unchanged fragments.
- Declarations with no change, which have no row.

## References

- [Details: design and tests](14-expand-to-declaration-details.md)
- [Structural outline](01-structural-outline.md)
- [Outline caret follow](07-outline-caret-follow.md), whose row lookup R2 uses
- [Product scope](../README.md#product-scope) P3 and P8
- [Roadmap: Expand context to the enclosing declaration](../../ROADMAP.md#expand-context-to-the-enclosing-declaration)
