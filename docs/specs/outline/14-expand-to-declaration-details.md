# Expand to declaration: details

Status: implemented, 2026-11-16

**Feature:** [Expand to declaration](14-expand-to-declaration.md)

## Design

### The platform's collapsed fragments

With "Collapse unchanged fragments" on, `SimpleDiffViewer` and the other two-sided text viewers hand the unchanged line ranges to a `FoldingModelSupport`. It adds one `FoldRegion` per side for each unchanged block through `FoldingModelSupport.addFolding`, which covers whole lines (from the start of the first line to the end of the last), uses a placeholder of five spaces and mutes the region's inner highlighters. The two regions of a block form a `FoldedBlock`, and its `MyFoldingListener` copies a region's expanded state to the paired region on the other side at the end of each folding batch. Clicking a fragment's separator expands the whole block. `DiffUtil.createEditor` turns auto code folding off for diff editors, so language folds are not built there.

The feature works through this public surface only: it reads and sets `FoldRegion.isExpanded` on the viewer's editors inside `FoldingModel.runBatchFoldingOperation`. It does not reach into `FoldingModelSupport` (its blocks are private), does not add, remove or split regions, and does not touch the viewer's settings (R10).

### Which regions are unchanged fragments

`UnchangedFragments.collapsed(editor)` returns the editor's fold regions that are valid, collapsed, start at a line start, end at a line end and have a placeholder that is blank. That matches what `FoldingModelSupport.addFolding` creates. A manual code fold the user made with the editor's folding actions usually has a non-blank placeholder such as `{...}` and is left alone. When "Collapse unchanged fragments" is off there are no such regions, so the action is hidden (R8).

### The row's ranges (R3, R7)

For an `OutlineItem`, the ranges on each side are the same ones `OutlineNavigator.select` frames: `oldRange` and `newRange`, plus each entry of `frames`, so a relationship row covers every declaration it links (R7). A container row shown only because of changes under it uses its own range, which covers its children. A group row (`isGroup`) and a `FILE_REGION` row give no ranges: the file region's range is the whole file, and expanding it is the platform's own "expand all" (R8). An `UNANALYZED` row keeps its ranges, since they are the lines the engine could not read. Ranges are clamped to the document as in `OutlineNavigator`, because the documents may have changed since the comparison.

### Expanding (R3 to R6)

`ExpandDeclaration.expand(oldEditor, newEditor, item)`:

1. For each side with an editor and ranges, convert each range to the lines `[firstLine, lastLine]` that contain it.
2. Select the side's collapsed fragments whose lines overlap any of those line ranges (R3). A fragment only partly in the declaration is selected and expands whole (R5); the rest are not touched (R6).
3. Run one `runBatchFoldingOperation` per editor that sets `isExpanded = true` on its selected regions.

Both sides are processed, so a fragment counts when it overlaps the row on either side, and a row present on one side only, such as an added method, still expands the fragments around it on that side. The platform's listener then expands the paired region on the other side (R4). Setting both sides directly does the same and does not depend on the listener's timing; the listener's own guard stops it from echoing the change back.

After expanding, each editor that has a range scrolls to the first line of its first range with `DiffUtil.scrollEditor(editor, line, false)`, inside `controller.withSuppressedCaretFollow` and with synchronized scrolling paused as `OutlineNavigator.select` does, so caret follow does not move the outline selection (R9). The outline is not rebuilt and no comparison starts (R10). The platform keeps the expanded state in its folding cache for the rest of the diff's life, as when the user clicks a separator; a new comparison by the platform may collapse fragments again, which is its behaviour.

### The outline's context menu (R1, R8)

`OutlinePanel` adds `expandDeclarationAction` to the popup group installed on the tree, before the reviewed-mark actions and followed by a separator. Its `update` takes `selectedItem()`, computes the regions as above and sets `isEnabledAndVisible` only when at least one region would expand (R8). The panel does not own the editors, so the action calls `controller.expandDeclaration(item)`, which owns `oldEditor` and `newEditor`. The update runs on the EDT (`ActionUpdateThread.EDT`), because it reads the folding model.

### The editor's context menu (R2, R8)

`ExpandDeclarationAction` is registered in `plugin.xml` with id `SmartDiff.ExpandDeclaration`, text "Expand Declaration", added to `Diff.EditorPopupMenu` and with no default shortcut, so a user can assign one in the keymap. The group id exists in 2025.1 (`IdeActions.GROUP_DIFF_EDITOR_POPUP`), where the two-sided text viewers build their editor context menu from it, so it is the right group and no substitution is needed.

- **Finding the outline:** the controller puts itself in the user data of `oldEditor` and `newEditor` under `OutlineController.EDITOR_KEY` in `start()`, and removes it on dispose. The action reads `CommonDataKeys.EDITOR` and then that key; with no controller, for example in a one-sided or three-sided diff or a diff the outline did not attach to, the action is hidden.
- **Finding the row:** with the state `OutlineState.Shown`, the controller's `matchingItem(items, side, offset)` from caret follow (outline/07) gives the innermost shown row for the caret offset on that editor's side. The controller exposes it as `rowAt(side, offset)`. A row hidden by the filter is not shown and is not found. With no match, or a match that has no region to expand, the action is hidden (R8).
- The action expands that row as above. It does not select the row in the outline; caret follow does that as usual once the caret moves.

### Edge cases

- **Stale result:** while a comparison is pending after an edit, the shown result's ranges can be off by a few lines. The action still runs on them, clamped; at worst it expands one fragment too many, which hides nothing.
- **Outline minimised or hidden:** the outline's menu is not reachable, and the editor action still works while a result is kept, since the controller keeps its state while minimised. When the outline was never shown there is no result and the action is hidden.
- **Formatting-only rows inside a group:** each row inside a `Formatting-only (3)` group is a change row and has the action; the group row itself does not (R8).
- **Ignore whitespace policies:** the platform's fragments already follow the diff's whitespace policy, so the action needs no special case.

## Tests

| Requirement | Check |
| --- | --- |
| R1, R3, R6 | `ExpandDeclarationTest` `test outline 14 R1 R3 R6 the row action expands only fragments in the declaration`: a fixture with a long changed method between two long unchanged methods, opened in a `SimpleDiffViewer` with collapsing on; the action on the method row expands the fragments inside the method and leaves the fragments in the other methods collapsed |
| R2 | `ExpandDeclarationTest` `test outline 14 R2 the editor action uses the row at the caret`: with the caret on the changed line of the new side, `ExpandDeclarationAction` expands the method's fragments; with the caret on an unchanged method it is hidden |
| R4 | `ExpandDeclarationTest` `test outline 14 R4 both sides expand`: after the action, the paired regions on both editors are expanded |
| R5 | `ExpandDeclarationTest` `test outline 14 R5 a fragment that crosses the declaration expands whole`: a fragment that starts inside the method and ends in the next method is expanded in full |
| R7 | `ExpandDeclarationTest` `test outline 14 R7 a relationship row expands each declaration it frames`: a move fixture in the refactorings view expands the fragments at both ends |
| R8 | `ExpandDeclarationTest` `test outline 14 R8 hidden for groups the file region and rows with nothing to expand`: the action's presentation is hidden for the formatting-only group, the file region, a row whose fragments are already expanded and a diff with collapsing off |
| R9 | `ExpandDeclarationTest` `test outline 14 R9 the declaration's first line is shown and the selection kept`: after the action the first line of the row's range is in each editor's visible area and the selected row is the same |
| R10 | `ExpandDeclarationTest` `test outline 14 R10 expanding does not compare again`: the controller's comparison count and the outline's model are unchanged, and the viewer's collapse setting is the same |
| fragment detection | `UnchangedFragmentsTest`: a blank-placeholder whole-line region is a fragment; a region with a `{...}` placeholder or that does not cover whole lines is not |

The tests use the platform's real `SimpleDiffViewer` through the same `DiffContents` helpers as `OutlineIntegrationTest`, with `TextDiffSettings.isExpandByDefault = false` and a small context line count, so the platform builds the fragments itself. They do not build fold regions by hand, except in `UnchangedFragmentsTest`.

The manual check adds one row to the release checklist: in a Java diff with collapsing on, "Expand Declaration" from the outline and from the editor menu shows the whole method on both sides and leaves the other methods collapsed.
