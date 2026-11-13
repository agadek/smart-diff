# Outline caret follow: details

Status: accepted, 2026-11-13

Feature: [Outline caret follow](07-outline-caret-follow.md)

## Design

### Where it plugs in

`OutlineController` owns a caret follower for its `TwosideTextDiffViewer`. On `start()` it adds a `CaretListener` to
`viewer.editor1` (old side) and `viewer.editor2` (new side), registered with the controller as the parent disposable, so
closing the diff removes them. Only the caret of these two editors is followed; the Changes tree follower
([changes-tree/05](../changes-tree/05-changes-tree-caret-follow.md)) is a separate project service and is not changed.

### From caret to row

1. A caret event restarts a `javax.swing.Timer` of 100 ms on the EDT and remembers the side and offset (R5). The value
   matches the Changes tree follower and the platform's autoscroll-from-source delay.
2. When the timer fires, following stops if the outline is hidden or minimised, its state is not `Shown`, a comparison
   is pending or running (R8), or the outline tree has focus (R5).
3. It walks the rows the tree shows, depth first in tree order, and keeps every row that is not a group and whose range
   on the caret's side contains the offset: `oldRange` for the old side, `newRange` for the new side (R2). A range
   contains the offset when `start <= offset < end`, or `offset == end` for the last character of the document.
4. Among the matches it picks the one with the shortest range on that side, the innermost declaration (R1). On a tie
   it prefers a row whose kind is not `RELATIONSHIP`, so in the [refactorings view](../../GLOSSARY.md#refactorings-view)
   the declaration's row under "Other changes" wins over a relationship row with the same range (R6); among the rest,
   the first in tree order wins.
5. No match keeps the current selection (R3). A match that is already selected does nothing.
6. Otherwise the tree selects the row and scrolls it into view through `OutlinePanel.reveal`, in follow mode.

Rows hidden by the change type filter are not in the tree, so they can never match (R7).

### Selecting without scrolling

`OutlinePanel`'s tree selection listener calls `OutlineNavigator.select(item)`, which scrolls both editors to the row
and may start `FrameScrollSync`. A selection made by following, and a selection restored after a rebuild
([outline/01](01-structural-outline.md) R27), goes through `OutlineNavigator.select(item, scroll = false)` instead:

- it frames the row and highlights its detail spans on both sides as a click does (R4);
- it calls no `DiffUtil.scrollEditor` and moves no caret, so the editor the caret is in stays where it is;
- it starts no `FrameScrollSync`, so the platform's synchronized scrolling keeps working;
- it never requests focus, and the outline's selection change does not move focus to the tree (R4).

The panel carries the mode through a flag set around its own `tree.selectionPath` assignment, so a click or a keyboard
selection in the tree still scrolls as [outline/01](01-structural-outline.md) R17 says.

### Rebuilt rows (outline/01 R27)

`OutlineController.showResult(keepSelection = true)` reveals the previously selected path after the rows are rebuilt, for
example when the reference pass replaces the engine result. That reveal uses the no-scroll mode, so the editors stay where
the user scrolled them while the pass ran. A reveal for a newly opened diff (the `reveal` path from the Changes tree)
still scrolls.

### Edge cases

- A row present on one side only has no range on the other side; a caret on that other side never selects it.
- A caret inside a nested declaration whose own row is filtered away selects the shown container row if the container's
  range contains the caret; otherwise the selection stays.
- Edits move the caret; the result is stale until the next comparison, so step 2 skips them (R8). After the new result
  is shown the controller runs the follow once for the last caret position.

## Tests

| Requirement | Test |
| --- | --- |
| R1, R2 | `OutlineCaretFollowTest` `test the caret on the new side selects the changed method under it`, `test the caret on the old side selects by the old range`, `test a nested declaration selects the innermost row` |
| R3 | `OutlineCaretFollowTest` `test a caret outside every changed declaration keeps the selection` |
| R4 | `OutlineCaretFollowTest` `test following frames the row without scrolling either editor or moving focus` |
| R5 | `OutlineCaretFollowTest` `test following leaves the selection alone while the outline has focus`, `test caret moves are debounced` |
| R6 | `OutlineCaretFollowTest` `test the refactorings view selects the row under Other changes` |
| R7 | `OutlineCaretFollowTest` `test a row hidden by the filter is never selected` |
| R8 | `OutlineCaretFollowTest` `test a stale result is not followed until the new result is shown` |
| R9 | Plugin Verifier reports no new internal, experimental or deprecated usage |
| outline/01 R27 | `OutlineIntegrationTest` `test a rebuild keeps the selected row without scrolling the editors` |

Tests use `BasePlatformTestCase` with a real two-sided diff viewer, as `OutlineIntegrationTest` does, and drive the
debounce timer through a test hook that fires it at once.
