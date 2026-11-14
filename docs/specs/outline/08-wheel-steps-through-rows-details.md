# Wheel steps through rows: details

Status: implemented, 2026-11-14

Feature: [Wheel steps through rows](08-wheel-steps-through-rows.md)

## Design

### Where it plugs in

`OutlinePanel` builds the outline's `Tree` and wraps it in a scroll pane (`ScrollPaneFactory.createScrollPane(tree,
true)`). A `MouseWheelListener` is added to the tree. In Swing, a component with a wheel listener receives the wheel
events instead of its scroll pane, so the listener must hand every event it does not use to the scroll pane itself:
`scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(tree, event, scrollPane))`, or an equivalent that keeps the
platform's normal and smooth scrolling (R5, R7).

### Stepping

1. An event with Shift held, or a horizontal scroll, is forwarded unchanged (R7).
2. With no selected row, the event is forwarded unchanged (R5).
3. Otherwise the listener consumes the event and adds `event.preciseWheelRotation` to an accumulator. Each time the
   accumulator passes +1 or -1 it steps once down or up and moves the accumulator back by 1, so a trackpad swipe of many
   small events moves one row per full notch (R6). The accumulator resets when the direction changes or the selection
   changes from outside the wheel.
4. A step walks the tree's visible rows (`tree.getRowForPath`, `tree.getPathForRow`) from the selected row in the step's
   direction and stops at the first row whose `OutlineItem` is not a group (`isGroup` is false), skipping section headings
   and the formatting-only, uncertain and unanalyzed group rows (R3). Rows inside collapsed nodes are not visible and are
   not visited.
5. When no such row exists in that direction, nothing changes (R4).
6. Otherwise the tree's selection path is set to that row and `tree.scrollPathToVisible` keeps it in view. The existing
   selection listener then shows it in the editors as an arrow-key selection does (R2). No focus is requested (R8).

### Edge cases

- Evidence rows under a relationship row are ordinary rows and are visited when their parent is expanded.
- A row selected while the comparison result is replaced keeps the existing selection logic; the accumulator resets.

## Tests

| Requirement | Test |
| --- | --- |
| R1, R2 | `OutlineWheelTest` `test a wheel notch down selects the next row and shows it in the editors`, `test a wheel notch up selects the previous row` |
| R3 | `OutlineWheelTest` `test the wheel skips section headings and group rows` (refactorings view and a formatting-only group) |
| R4 | `OutlineWheelTest` `test the wheel stops at the first and last row` |
| R5 | `OutlineWheelTest` `test with no selection the wheel scrolls the outline` |
| R6 | `OutlineWheelTest` `test small precise wheel events move one row per full notch` |
| R7 | `OutlineWheelTest` `test a horizontal or shift wheel scroll does not change the selection` |
| R8 | `OutlineWheelTest` `test stepping does not move focus` |

Tests dispatch real `MouseWheelEvent`s to the tree with `BasePlatformTestCase` and a real two-sided diff viewer, as
`OutlineIntegrationTest` does.
