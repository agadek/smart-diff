# Outline settings button: details

Status: proposed, 2026-11-14

Feature: [Outline settings button](10-outline-settings-button.md)

## Design

### The button

A new `OpenSmartDiffSettingsAction`, a `DumbAwareAction` in `io.github.agadek.smartdiff.ui`, is shared by both outline panels:

- Text `SMART Diff Settings`, description `Open Settings | Tools | SMART Diff`, icon `AllIcons.General.GearPlain`.
- `getActionUpdateThread()` returns `BGT`, like the refresh action. `update` leaves the presentation visible and enabled in every state (R4), because the panel and its toolbar stay the same through `Comparing…`, a result, a fallback message and `NoEngine`.
- `actionPerformed` calls `ShowSettingsUtil.getInstance().showSettingsDialog(e.project, SmartDiffConfigurable::class.java)` (R2). This is public, non-experimental platform API, so Plugin Verifier stays clean. A diff without a project, such as one opened from the welcome screen, passes `null` and gets the default project's settings dialog, which holds the same application-level page.
- The call goes through an internal `settingsOpener: (Project?) -> Unit` seam on the action's companion object, so tests can record the call instead of opening a modal dialog.

Placement in the toolbars (R1, R3):

- `OutlinePanel`: `DefaultActionGroup(viewAction, filter, kindsAction, refresh, settingsAction, minimiseAction, hideAction)`. Minimise and Hide stay at the end, as the window controls of tool windows do.
- `MergeOutlinePanel`: the button is added after `refresh` when `controller.showsRefresh`, and after the filter otherwise, so it is the last action in the three-sided diff and in the merge window.

The minimised strip has no button: it has no toolbar, and putting the panel back is one click.

### Applying the settings to open outlines

Today only the Changes tree listens to `SmartDiffSettings.CHANGED`; an open outline reads the settings only when it is rebuilt. Both controllers subscribe to the topic with themselves as the parent disposable, so the subscription ends with the viewer:

```kotlin
ApplicationManager.getApplication().messageBus.connect(this)
    .subscribe(SmartDiffSettings.CHANGED, SmartDiffSettings.Listener { settingsChanged() })
```

`SmartDiffConfigurable.apply()` already publishes the topic on OK and Apply, and a cancelled dialog publishes nothing, so R9 needs no code. The topic is also published by toolbar toggles in the Changes tree; the snapshots below make those events no-ops for the outline.

Each controller keeps two snapshots taken when it starts and after each event:

- **Display snapshot** (R5): for `OutlineController`, `hiddenChangeTypes`, `formattingOnlyInline`, `outlineView` and `hiddenRelationshipKinds`; for `MergeOutlineController`, `hiddenChangeTypes`, `mergeGroupedBySide` and `mergeHideBothSides`.
- **Analysis snapshot** (R6): the id of `selection().selected` for the controller's `languageId`, `SmartDiffSettings.limits` and `useStructureViewFallback`. `analysisRevision` is not used on its own because it also moves with `formattingOnlyInline`, which is display-only for the outline, and the engine choice is tracked by `EngineDefaults.revision`, not by the settings state.

`settingsChanged()` runs on the EDT and does nothing when the controller is disposed or the outline is hidden. Otherwise:

1. If the analysis snapshot changed, it calls `refresh()`, which cancels a running comparison and compares again. The rebuild in step 2 is skipped, because the new result is shown with the current display settings.
2. Else, if the display snapshot changed and the state is a result, it calls the existing `rebuild()`, the same path the toolbar's filter and view toggles use, which builds the tree from the kept result with `keepSelection` and without comparing again.
3. Else nothing happens, so an unrelated change, such as a Changes tree option, leaves the outline untouched.

`rebuild()` restores the selected item by its outline path when the item is still shown after the change (R7); a selected row that is now hidden leaves no selection, as the toolbar filter already does. A hidden outline picks the settings up when it is shown again, because showing it rebuilds or compares.

`showOutline` is in neither snapshot. It only chooses the starting state of new diffs (settings/01 R3), so turning it off leaves open outlines as they are (R8).

### Edge cases

- **Several open diffs:** each controller has its own subscription and snapshots, so one Apply updates every open outline (R5).
- **Engine dropdown:** choosing an engine in the dropdown already sets the language's default and compares again; the event that follows finds the analysis snapshot equal to the one the dropdown already updated, so it does not compare twice. The controller updates its analysis snapshot in `chooseEngine` before it compares.
- **Comparison running:** an analysis change cancels it and starts again; a display change while `Comparing…` only updates the snapshot, and the result is built with the new settings when it arrives.
- **Merge window with result edits:** `refresh()` follows the same path as a result-pane edit, so conflict ranges and the result pane stay consistent.

## Tests

| Requirement | Check |
| --- | --- |
| R1 | `OutlineIntegrationTest` `test outline 10 R1 the toolbar has the settings button after refresh`: the panel's toolbar actions contain `OpenSmartDiffSettingsAction` right after the refresh action, with the gear icon and the tooltip text |
| R2 | `OutlineIntegrationTest` `test outline 10 R2 the button opens the SMART Diff settings`: performing the action with the test `settingsOpener` records one call with the diff's project |
| R3 | `MergeOutlineIntegrationTest` `test outline 10 R3 the merge outline has the settings button`: the three-sided outline and the merge window outline both end their toolbar with the action |
| R4 | `OutlineIntegrationTest` `test outline 10 R4 the button is enabled in every state`: the action's presentation is visible and enabled while `Comparing…`, with a result, with a `TOO_LARGE` fallback and with `NoEngine` |
| R5 | `OutlineIntegrationTest` `test outline 10 R5 applied display settings rebuild the open outline`: changing `hiddenChangeTypes`, `formattingOnlyInline` and `outlineView` and publishing `CHANGED` changes the shown rows without a new comparison (the engine's call count stays the same); `MergeOutlineIntegrationTest` `test outline 10 R5 applied display settings rebuild the merge outline` does the same for `mergeHideBothSides` |
| R6 | `OutlineIntegrationTest` `test outline 10 R6 analysis settings compare again`: changing `limits` and the language's default engine and publishing `CHANGED` compares once more each time; publishing `CHANGED` with no change, or with only a Changes tree option changed, compares no more |
| R7 | `OutlineIntegrationTest` `test outline 10 R7 a rebuild keeps the selected row`: a row selected before a display change that keeps it shown is still selected after the event |
| R8 | `OutlineIntegrationTest` `test outline 10 R8 turning off automatic opening keeps an open outline`: setting `showOutline` to false and publishing `CHANGED` leaves the panel in the splitter |
| R9 | Manual: open the button's dialog in a running IDE, change several options, press Cancel, and check the outline, its selection and the editors are unchanged |

The manual check for the PR also covers R1 to R3 by sight in IC 2025.1 and the latest release: the gear is in the two-sided, three-sided and merge-window outlines, opens the *Tools | SMART Diff* page, and OK applies a changed filter to the open outline.

## Open questions

None.
