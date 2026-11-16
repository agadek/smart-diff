# Moved-code markers: details

Status: proposed, 2026-11-16

Feature: [Moved-code markers](13-moved-code-markers.md)

## Design

### Which rows get a marker

The markers are computed from the rows the outline shows, not from the raw result, so they follow every filter the outline applies (R2, R3) without repeating its rules.

A new pure function `MovedCodeMarkers.targets(items: List<OutlineItem>): List<MovedTarget>` walks the shown rows depth-first, children included, and returns one `MovedTarget(path: OutlinePath, title: String, oldRange: TextRange, newRange: TextRange)` for each row that:

- is not a group (`isGroup` is false), and
- has both `oldRange` and `newRange`, and
- in the container tree, carries `Tag.MOVED`; in the refactorings view, is a relationship row whose relationship's kinds contain `RelationshipKind.MOVE`.

The refactorings view does not mark its rows by kind today. `RefactoringsModel` adds a `moved: Boolean` to the relationship rows it builds (true when the kinds contain `MOVE`), and `OutlineItem` gains that field with a default of `false`, kept by `withChildren`. The container tree sets it from `Tag.MOVED`, so `targets` reads one field in both views. A relationship row with several endpoints uses its primary ranges, the ones the row frames first.

`title` is the row's `title`, used in the tooltip. Targets are sorted by `newRange.start`, so tests and the marker order are stable. A declaration listed twice, which the outline does not do today, gets one target per row; nothing deduplicates on purpose.

### The highlighters

A new `MovedCodeMarkers(viewer: FrameDiffTool.DiffViewer, onClick: (OutlinePath) -> Unit)` in `io.github.agadek.smartdiff.ui`, shaped like `RelationshipHighlights`:

- `show(targets: List<MovedTarget>)` clears, returns at once when the viewer is not a `TwosideTextDiffViewer`, and otherwise adds one highlighter per target on each editor.
- `clear()` disposes every highlighter it added. `shown` exposes them for tests.

Each highlighter is added with `editor.markupModel.addRangeHighlighter(start, end, HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.LINES_IN_RANGE)`, where `start..end` is the target's range on that side, clamped to the document as `RelationshipHighlights.add` does. Passing no text attributes leaves the diff's colours and the text unchanged (R4).

On each highlighter:

- `gutterIconRenderer` is a `MovedCodeGutterIcon`, a `GutterIconRenderer` with `Alignment.LEFT`. The platform draws it at the highlighter's first line (R2). Its icon is one `AllIcons` icon used on both sides (R4); `AllIcons.Actions.MoveTo2` is the first choice, and any public `AllIcons` arrow that Plugin Verifier accepts on the whole supported range is fine. `equals` and `hashCode` compare the side and the target, as `GutterIconRenderer` requires.
- The tooltip (R5) is `"<title>: Moved from line N"` on the new side and `"<title>: Moved to line N"` on the old side, where N is `document.getLineNumber(otherRange.start) + 1` in the other editor's document.
- `errorStripeMarkColor` is a single `JBColor` constant, `MOVED_MARK`, a muted blue with light and dark values, and `isThinErrorStripeMark` is true, so the mark sits beside the diff's own change marks instead of covering them (R6). `errorStripeTooltip` is the same text as the gutter tooltip.
- `getClickAction()` returns a `DumbAwareAction` that calls `onClick(target.path)` (R7).

The ranges are offsets into the compared texts, which are the editors' documents in a two-sided diff, as `RelationshipHighlights` already relies on.

### Wiring into the controller

`OutlineController` owns one `MovedCodeMarkers` next to `usageHighlights`, created with `onClick = ::revealFromMarker`:

- `showResult` calls `movedMarkers.show(MovedCodeMarkers.targets(items))` after `show(...)` when `SmartDiffSettings.markMovedCode` is on, and `movedMarkers.clear()` when it is off (R1, R2). `showResult` is also the path `rebuild()` takes for the toolbar filter, the view switch and applied settings, so R12 needs no other call.
- `show(state)` clears the markers for every state that is not `Shown` (R11), in the same place that clears `usageHighlights`.
- `documentChanged()` clears them with the usage highlights (R10); the next `showResult` adds the new ones.
- `hideOutline()` clears them; showing the outline compares or rebuilds, which adds them back (R9).
- `dispose()` clears them.
- `setMinimised(true)` keeps them, because the result is still shown (R8).

`revealFromMarker(path)` runs on the EDT. It does nothing when the controller is disposed or the state is not `Shown`. Otherwise it calls `setMinimised(false)` when the outline is minimised (R8), then `panel.reveal(path)`, which selects the row and lets the navigator frame both sides and scroll the other editor, the same path a row click takes (R7). A path that no longer resolves selects nothing.

### The setting

`SmartDiffSettings.SettingsState` gains `var markMovedCode: Boolean = true` (R1, settings/01 R20). `SmartDiffConfigurable` adds the checkbox "Mark moved code in the editors" under the outline options, after "Mark comment-only changes" (R13). The outline/10 display snapshot gains `markMovedCode`, so an applied change rebuilds open outlines without comparing again (R12, R13).

### Edge cases

- **Moved declaration inside a moved container:** each row the outline lists as moved gets its own marker; nested markers on different lines do not collide. Two targets that start on the same line get two icons, which the platform lays out side by side.
- **Range on a line the diff hides:** the outline never lists hidden rows, so no marker lands on a line `FilteringDiffViewer` hides.
- **Engine reports `PARTIAL`:** markers come from the shown rows, so the analyzed part gets markers and the unanalyzed group gets none.
- **Not a two-sided text diff:** `show` adds nothing; the outline is unchanged.
- **Reference pass still running:** the rows shown before the pass finishes already have their markers, and the rebuild after the pass replaces them.

### Failure

Adding highlighters cannot fail on valid ranges; ranges are clamped first. If a target's range is empty after clamping, that side gets no marker and the other side keeps its own.

## Tests

| Requirement | Check |
| --- | --- |
| R1 | `MovedCodeMarkersTest` `test outline 13 R1 the setting is on by default and off adds no marker`: a fresh `SettingsState` has `markMovedCode` true; with it false, a result with a moved method leaves both markup models without moved-code highlighters |
| R2 | `MovedCodeMarkersTest` `test outline 13 R2 a moved declaration has one marker on each side`: a Java fixture that reorders two methods and moves a nested class shows one gutter icon per moved row on each editor, at the first line of each range; the refactorings view of the same fixture shows the same markers |
| R3 | `MovedCodeMarkersTest` `test outline 13 R3 hidden moved rows have no marker`: hiding `MOVED` in the change type filter, and hiding `MOVE` in the relationship kind filter in the refactorings view, removes the markers |
| R4 | `MovedCodeMarkersTest` `test outline 13 R4 the icon is the same on both sides and adds no colour`: both renderers return the same icon; the highlighters have no text attributes |
| R5 | `MovedCodeMarkersTest` `test outline 13 R5 the tooltip names the other side's line`: the new side reads `applyDiscount: Moved from line 42`, the old side `applyDiscount: Moved to line 17` for the fixture's positions |
| R6 | `MovedCodeMarkersTest` `test outline 13 R6 each marker has a thin scrollbar mark`: every highlighter has `errorStripeMarkColor == MOVED_MARK` and a thin mark |
| R7 | `OutlineIntegrationTest` `test outline 13 R7 clicking a marker selects the row`: performing the click action selects the moved row and the navigator frames both ranges |
| R8 | `OutlineIntegrationTest` `test outline 13 R8 clicking a marker while minimised shows the panel`: with the outline minimised, the click shows the full panel and selects the row |
| R9 | `OutlineIntegrationTest` `test outline 13 R9 hiding the outline removes the markers`: hiding removes every moved-code highlighter, and showing adds them back |
| R10 | `OutlineIntegrationTest` `test outline 13 R10 an edit replaces the markers`: an edit to the new side removes the markers at once, and the next result adds markers at the new positions |
| R11 | `OutlineIntegrationTest` `test outline 13 R11 fallback states show no markers`: `Comparing…`, `TOO_LARGE` and `NoEngine` leave no moved-code highlighters |
| R12 | `OutlineIntegrationTest` `test outline 13 R12 filter and view changes update the markers without comparing`: toggling the filter, switching the view and publishing `CHANGED` after turning the setting off update the markers while the engine's call count stays the same |
| R13 | `SmartDiffSettingsTest` `test outline 13 R13 the settings page has the checkbox`: the configurable's checkbox reads and writes `markMovedCode`, and `isModified` follows it |

The manual check for the PR looks at a reordered Java and Kotlin file in IC 2025.1 and the latest release: the icons sit in the gutter on both sides in light and dark themes, the scrollbar marks are visible beside the diff's change marks, the tooltip reads correctly, and a click frames both positions.

## Open questions

None.
