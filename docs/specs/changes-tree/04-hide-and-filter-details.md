# Hide and filter in the Changes tree: details

Status: proposed, 2026-11-05

Feature: [Hide and filter in the Changes tree](04-hide-and-filter.md)

## Design

### Where it plugs in

- `ChangesSummaryModifier`, the `vcs.changes.changesViewModifier` extension in `smartdiff-vcs.xml`, already runs while the platform builds the Local Changes and Commit trees. It reads the tree settings and decides what to put on each file row.
- `SummaryChangeNode` renders the platform's file row and, when the setting is on, appends the grey summary or reason. `DeclarationNode` renders one outline row and already exposes its label through `getTextPresentation` for speed search.
- `ChangesSummaryService` keeps the cache of computed `FileSummary` values. `FileSummary` keeps the summary text without a hidden suffix and the unfiltered outline rows, so the tree can filter and recount without asking an engine to compare again.
- `SmartDiffSettings` holds the two toggles and the tree's own hidden types. `SmartDiffConfigurable` shows the same settings on the Settings page.
- `smartdiff-vcs.xml` registers the new actions, so an IDE without VCS support does not load them.

### View Options actions

The 2025.1 platform declares an empty group `ChangesView.ViewOptions` in `META-INF/VcsActions.xml`. `ChangesViewManager.createChangesToolbarActions` adds that group to the eye-icon View Options popup of the Local Changes and Commit tree. The plugin adds its actions to the group in `smartdiff-vcs.xml`:

```xml
<action id="SmartDiff.ChangesTree.StructuralSummary" .../>
<action id="SmartDiff.ChangesTree.Declarations" .../>
<group id="SmartDiff.ChangesTree.HideChangeTypes" popup="true" .../>
```

Each action and the group use `<add-to-group group-id="ChangesView.ViewOptions" anchor="last"/>`. This is XML action registration, not an internal class, and it puts the actions below the platform's own "Show" options. The group is checked in a manual run on 2025.1 and the latest release. If it is absent or does not accept plugin actions on a supported build, the same actions are added to `ChangesViewToolbar` instead, which makes them toolbar buttons; R1 accepts either. The plugin does not wrap the tree renderer or use `ChangesViewManager`'s internals.

The two toggles are `DumbAwareToggleAction`s with stable action IDs. Their `isSelected` reads `SmartDiffSettings.showChangesSummary` and the new `showChangesDeclarations`. Their `setSelected` writes the setting and publishes `SmartDiffSettings.CHANGED`, whose listener rebuilds every open Changes tree. The "Hide Change Types" group holds one `DumbAwareToggleAction` per `ChangeType`, with the same labels as the outline's menu. A checked item means hidden, as in the outline. All actions use `ActionUpdateThread.BGT`, so reading the settings stays off the UI thread.

### Toggles and the file row

`ChangesSummaryModifier.modifyTreeModelBuilder` reads both toggles:

| Structural Summary | Declarations | File row |
| --- | --- | --- |
| off | off | The platform's own row. The modifier returns without replacing anything. |
| on | off | The platform's row plus the grey summary or reason, with no children, so the row is a leaf again. |
| off | on | The platform's row with the declaration rows under it, and no grey text. |
| on | on | The platform's row, the grey summary or reason, and the declaration rows. |

- The modifier still considers only files that `ChangesSummaryService` can summarize: modified or moved files whose language has an engine, within the first 500 file nodes. A file with no known summary stays as the platform's row until the summary arrives, as today.
- `SummaryChangeNode.render` asks the original platform node to render itself, then appends the summary only when `showChangesSummary` is on. The existing summary text and the `not analyzed: …` reasons are both grey text; R3 hides both.
- Declaration children are added only when `showChangesDeclarations` is on and the filtered rows are not empty. `shouldExpandByDefault` stays false, so an expanded file can collapse.
- With Declarations off, the file node has no children and its selection, inclusion, checkbox, diff preview and double-click/Enter handling are the platform's. This is the path that depends on issue #111: before that fix, a file row with children could not open its diff, and the leaf check is only meaningful once the row's own behavior is fixed.
- A file with a reason instead of a summary has no rows either way; turning Declarations on does not create an empty expand arrow.

### The tree's filter

The tree reuses the outline's [change types](../outline/03-change-type-filter.md) and `ChangeType` enum, so the menu labels and the tags behind each type are the same. The rows kept in `FileSummary.items` are the outline's rows built without a filter. Filtering them is a pure tree pass:

1. An item is a change when it has at least one tag. Groups and containers have no tags.
2. A change is hidden when every one of its tags belongs to a hidden type. A tag that is not in any `ChangeType.tags` keeps the item shown: `UNCERTAIN`, `RENAME_CANDIDATE` and `UNANALYZED` are never hidden, and a rename candidate that also has `ADDED` or `REMOVED` stays because of its rename tag.
3. Hidden changes are removed. A hidden change whose children survive stays as a container without tags, without its note, with its title and ranges, and with the surviving children. This matches outline/03 R4.
4. Groups with no surviving children are removed. The Uncertain and Unanalyzed groups stay because their contents are never hidden.
5. The pass counts each hidden change, including a hidden change that stays as a container, into `hiddenCount`. It matches `OutlineModel.hiddenCount` for the outline.
6. When the filtered rows are empty and `hiddenCount > 0`, every change is hidden. A file with no structural changes has `hiddenCount == 0` and is not "all hidden".

The result is a `FilteredRows(rows, hiddenCount)` value. `ChangeType` has five entries, so the filter state has at most 32 combinations and no per-file recomputation is needed.

### Summary line and all hidden

`FileSummary` keeps the summary text that the service computes without a filter, for example `2 changed · 1 added`. The modifier adds the suffix from the filter pass:

- no hidden changes: the summary is unchanged;
- some hidden changes: `· N hidden`;
- every change hidden: `· all hidden`, and the file has no declaration rows.

The base summary still counts every change, because it is computed before filtering. The hidden count counts changes, not labels, exactly as in the outline. A file whose summary is a reason has no rows and no hidden suffix. A file whose every change is hidden remains in the tree, selectable, includable and openable, because the filter only removes declaration rows (product rule P3).

### Settings and rebuilds

`SmartDiffSettings.SettingsState` gains:

| Field | Default | Meaning |
| --- | --- | --- |
| `showChangesDeclarations` | `true` | Show the declaration rows under each file; the tree's "Declarations" toggle |
| `hiddenChangesTreeChangeTypes` | `""` | The hidden types of the tree's filter, stored by `ChangeType` name like `hiddenChangeTypes` |

`showChangesSummary` already exists and keeps its `true` default. `hiddenChangesTreeChangeTypes` is parsed like `hiddenChangeTypes`: unknown names are ignored, and an empty value means nothing is hidden. It is a different field from `hiddenChangeTypes`, so the outline's filter and the tree's filter never change each other. Both filters start empty, which keeps product rule P3.

The Settings page adds a "Show declarations in the Changes tree" checkbox under the existing summary checkbox, and a separate "Hide from the Changes tree:" group with one checkbox per change type next to "Hide from the outline:". Both the page and the tree actions bind to the same accessors, so changing either place changes the other after the page is applied or the action runs. The existing comment that hidden changes still count in the summary applies to both filters.

Rebuilds go through `refreshChangesView`, which publishes `ChangesViewModifier.TOPIC`; the modifier runs again and reuses the cached `FileSummary`. The cache is not cleared for the two toggles or the tree's filter. To keep the existing "engine or limit change gives a new summary" behavior without clearing on every Apply:

- `SmartDiffSettings` marks a revision when an analysis-affecting setting changes: the limits and the formatting-only layout, which changes the cached rows. Display-only settings such as the two toggles, the two filters, the outline toggle and the merge settings do not mark it.
- `EngineDefaults` marks a revision when a language's engine changes.
- `ChangesSummarySettingsListener` remembers both revisions. On `SmartDiffSettings.CHANGED` it clears `ChangesSummaryService` only when one of them moved, then rebuilds every open project's tree.

The toggle and filter actions publish the same `CHANGED` event, so they rebuild every open tree, but they do not move an analysis revision and do not clear the cache. A test with a counting engine checks that a toggle or filter change leaves the call count unchanged; an engine or limit change still recomputes.

### Speed search

The tree already asks each node for `getTextPresentation`; `DeclarationNode` returns `item.label`, which includes the title and the labels, for example `method isEmpty()  added`. The filtered rows keep their labels, so speed search finds a shown declaration row by its label. A row the filter hides is not in the tree and is not found; with Declarations off there are no declaration rows to find. No new speed-search component is added.

### Dependencies and interactions

- **Issue #111 (file row double-click and Enter):** R4 and the "Declarations off" path assume the platform's file row behavior, including opening a diff from a file that used to have children. This feature should land after #111, and its tests should run against the fixed behavior. #111 also covers file rows with Declarations on, which still have children.
- **Issue #110 (tree follows the caret):** when it lands, the caret-to-row selection must use the filtered rows. If the declaration under the caret is hidden by the tree's filter, or the declaration rows are off, the nearest shown row above is selected, as in [changes-tree/02](02-changes-tree-declarations.md) R12; if there is none, the file row is selected. This feature does not implement #110.

## Tests

The tests name the requirement they check, for example `// changes-tree/04-hide-and-filter R7`. They build trees with `TreeModelBuilder` and the platform's `NoneChangesGroupingFactory`, as `ChangesSummaryTest` does, and use a counting test engine where a comparison must not run. The Java fixture is `Cart.java` from `ChangesSummaryTest`, extended with a signature change, a moved method and a formatting-only method so every filter type has a row.

| Requirement | Check |
| --- | --- |
| R1 | `ChangesTreeFilterTest` `test the view options group has the smart diff toggles and filter`: the action IDs are registered in `ChangesView.ViewOptions`, or in `ChangesViewToolbar` when the fallback is chosen; M1 checks the eye menu in a running IDE. |
| R2 | `ChangesTreeFilterTest` `test the summary and declaration toggles are independent`: all four combinations of the two settings give the expected summary and rows. |
| R3 | `ChangesTreeFilterTest` `test the structural summary toggle hides the grey text and keeps the rows`: a rendered file row has no summary or reason text, and its declaration children remain. |
| R4 | `ChangesTreeFilterTest` `test declarations off makes each file a leaf and leaves the row to the platform`: the file node is a leaf with no children, and `DeclarationNodeAction` leaves the row to the platform; M3 checks double-click and Enter after #111. |
| R5 | `SmartDiffSettingsTest` `test the changes tree toggles share the settings page values`; `ChangesTreeFilterTest` `test a tree toggle writes the same setting as the settings page` |
| R6 | `ChangesTreeFilterTest` `test toggling rebuilds without another comparison`: a counting engine runs once per file, and changing a toggle leaves the count unchanged. |
| R7 | `OutlineModelTest` `test the tree filter hides each change type`; `ChangesTreeFilterTest` `test each change type can be hidden from the tree` |
| R8 | `OutlineModelTest` `test hiding changed also hides a signature change` |
| R9 | `OutlineModelTest` `test a change with one shown label stays` |
| R10 | `OutlineModelTest` `test a hidden container with a shown child stays without labels`; `ChangesTreeFilterTest` `test a hidden container stays as a container in the tree` |
| R11 | `OutlineModelTest` `test uncertain rename candidate and unanalyzed rows are never hidden`; `ChangesTreeFilterTest` `test never hidden rows survive the tree filter` |
| R12 | `ChangesTreeFilterTest` `test the filter is a hide change types submenu`: the group has one toggle per `ChangeType` and lives in the same action group as the toggles; M1 checks it in the eye menu. |
| R13 | `ChangesSummaryTest` `test the tree summary counts hidden changes and ends with the hidden count`: `· 1 hidden`, `· 2 hidden`; the base counts do not change. |
| R14 | `ChangesSummaryTest` `test a file whose changes are all hidden stays with all hidden in its summary and no rows`: the file remains in the tree, has no children, is still includable, and ends with `· all hidden`. |
| R15 | `ChangesTreeFilterTest` `test speed search text of a declaration row is its label`: `getTextPresentation` equals the outline label for shown rows. |
| R16 | `ChangesTreeFilterTest` `test changing the tree filter leaves the outline filter unchanged`; `SmartDiffSettingsTest` `test the two filters are separate settings` |
| R17 | `SmartDiffSettingsTest` `test the changes tree filter is stored separately and starts empty`: the default is empty, unknown names are ignored, and the outline's stored value is untouched. |
| R18 | `ChangesTreeFilterTest` `test changing the filter rebuilds without another comparison`: a counting engine runs once per file, and changing the filter leaves the count unchanged. |

### Manual checks

M1 and M2 are the View Options and toolbar checks; the rest cover behavior that a platform test cannot see end to end.

| Check | Steps |
| --- | --- |
| M1 View Options placement | In Local Changes and in the Commit tool window, click the eye icon. Confirm "Structural Summary", "Declarations" and the "Hide Change Types" submenu appear below the platform's own options. Repeat on 2025.1 and the latest release. If the group cannot be extended, confirm the same actions appear in the tree's toolbar (R1, R12). |
| M2 Toggles rebuild at once | Toggle each action and watch the tree change without a comparison progress bar or a recomputed summary. Confirm the other toggle and the filter keep their state (R2, R6). |
| M3 Declarations off | Turn Declarations off: no file has an expand arrow; double-click and Enter on a summarized file row open its diff as for a file without a summary; selection, checkbox and commit include the file only. Turn it on: children return and the file row keeps its diff behavior (R4; after #111). |
| M4 Structural Summary off | Turn Structural Summary off: no grey summary after any file, and a file with a reason shows no grey reason either; if Declarations is on, its rows remain (R3). |
| M5 Settings page mirrors the tree | Open Settings \| Tools \| SMART Diff. Confirm the same two checkboxes and the separate "Hide from the Changes tree" group. Change one place, apply, and confirm the tree and the other place agree (R5, R17). |
| M6 Filter rules | Hide each type in turn. Confirm hiding changed also hides a signature change; a moved and changed method stays while either type is shown; uncertain, rename candidate and unanalyzed rows never disappear; a hidden container with shown children stays without labels (R7 to R11). |
| M7 Hidden counts and all hidden | Hide one type with two matching changes: the summary ends `· 2 hidden` and keeps its full counts. Hide every type for a file whose changes are all of those types: it stays in the tree, is still included in the commit, has no rows, and its summary ends `· all hidden` (R13, R14). |
| M8 Separate filters and persistence | Hide changed in the tree, then open a diff: its outline still shows changed rows. Hide moved in the outline, then reopen the tree: its rows are unchanged. Restart the IDE and confirm both filters and both toggles are remembered separately (R16, R17). |
| M9 Speed search | Type a shown declaration's name in the tree: the row is found. Hide its type or turn Declarations off: the row is not found (R15). |

## Open questions

- **Where the View Options actions attach.** 2025.1 declares `ChangesView.ViewOptions` as an empty public group and adds it to the eye popup, which is the preferred target. The latest release may differ. Options: (a) add to `ChangesView.ViewOptions` (inside the eye menu, preferred); (b) add to `ChangesViewToolbar` (always visible buttons); (c) add to both if the group is absent on some supported builds. The implementation checks 2025.1 and the latest release before choosing; R1 allows either visible placement.
- **The filter while Declarations is off.** Options: (a) keep "Hide Change Types" enabled, so the user can set it before turning the rows on (simplest); (b) disable it while Declarations is off, because it has no visible effect. The spec does not require either.
- **Expansion after turning Declarations back on.** Options: (a) the rebuilt tree starts as changes-tree/02 R6 describes, with files and containers collapsed (simplest); (b) remember the expanded declaration paths while the rows are off and restore them when the rows return. The spec does not require either.
