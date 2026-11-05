# Outline toggle: details

Status: implemented, 2026-11-05

Feature: [Outline toggle](02-outline-toggle.md)

## Design

### Issue milestones

| Issue #115 | Requirements |
| --- | --- |
| M1: header Hide button | R7 |
| M2: minimise to a strip, restore, keep computed | R8, R9, R10, R11 |
| M3: default shortcut in Keymap | R12 |
| M4: discoverable in a narrow window or overflow | R13 |
| M5: remember minimised state and width | R14 |

### Where it plugs in

- `OutlinePanel` keeps the panel header: the engine dropdown, the hide change types menu, Refresh, and the new Minimise and Hide actions.
- `OutlineController` owns the three states of one two-sided diff window and moves the panel or the strip into the viewer's component.
- `OutlineStrip` is the thin component shown while minimised. `ToggleOutlineAction`, the toolbar toggle, still reads and writes `OutlineController.isOutlineVisible` through `OutlineToggleTarget`.
- `plugin.xml` registers the toggle's default shortcut and its place in the diff toolbar.

### States

| State | Panel | Comparison | Rest of the diff window |
| --- | --- | --- | --- |
| Hidden | neither the panel nor the strip | canceled; no comparison runs (R3) | editors unchanged (R2) |
| Visible, full | the panel is the splitter's second component | runs and reruns after edits | editors keep their splitter width |
| Visible, minimised | the strip is at the viewer's right edge; the splitter has no second component | stays computed; edits still rerun it (R11) | editors take the panel's width |

The toolbar toggle is checked in both visible states. Turning it off from either state hides the outline; turning it on shows the last visible mode, full or minimised.

### Hide button

The header's Hide action uses `AllIcons.General.HideToolWindow`, the platform's tool window Hide icon, and calls `OutlineController.setOutlineVisible(false)`. That is the same path as the toolbar toggle's off state, so R2 to R6 hold: the panel disappears, the editors stay, the comparison stops, the choice goes into the diff window's `DiffContext`, and other windows keep their own choice. While minimised there is no header, so hiding is done from the toolbar toggle or its shortcut.

### Minimise and the strip

The header's Minimise action calls `OutlineController.setMinimised(true)`. The controller removes the panel from the splitter's second component, so the editors take its width, and adds `OutlineStrip` to `BorderLayout.EAST` of the diff viewer's `DiffPanelBase` (the same `BorderLayout` that `insertOutlineSplitter` uses). It does not call `refresh` or `cancel`, so the result and the rows stay as they are.

- The strip is 24 scaled pixels wide and fills the height. It shows `AllIcons.Toolwindows.ToolWindowStructure`, the same icon as the toolbar toggle.
- The icon is a `JButton`, so a click and the keyboard (Tab, then Space or Enter) restore the panel; a focus border shows where Tab is. Its accessible name is `Structural outline, minimised: <detail>. Click or press Enter to restore.`
- While a result exists, the strip's tooltip and accessible name carry the summary line, or the fallback title and reason when the result is `UNSUPPORTED`, `TOO_LARGE` or `FAILED` (R9). The strip never uses the empty summary line for a fallback, so it never claims `No structural changes` when the analysis did not run. Before a result exists they say `Structural outline, minimised: Comparing…`.
- Restoring removes the strip, puts the panel back as the splitter's second component, and does not compare again (R11). Because the splitter proportion was never changed while minimised, the panel returns to the width the user last chose (R10).
- A diff opened from a declaration in the Changes tree ([changes-tree/02](../changes-tree/02-changes-tree-declarations.md)) opens the full panel even when the remembered state is minimised, so the selected row is visible. The remembered state itself is not changed by that diff.

### Default shortcut

`plugin.xml` gives `SmartDiff.ToggleOutline` the default shortcut `control alt shift O` in `$default` (`Cmd+Option+Shift+O` on macOS, where the platform maps `control` to `meta`). It is free in the platform's default keymap and in the Mac OS X keymaps, including the diff shortcuts (`F7`, `Alt+Shift+Left/Right`, `Ctrl+Shift+Tab`, and so on). The action id stays stable, so Keymap shows the shortcut under SMART Diff and the user can change it there (R12). The action remains context-sensitive: outside a diff with an outline it is disabled and hidden, as before.

### Narrow diff window

The toggle is added to `Diff.ViewerToolbar` with `anchor="first"` instead of `anchor="last"`. Platform actions in that group use the default last anchor, so the toggle is the first action: a narrow toolbar moves the later actions into the `>>` overflow first, and the toggle stays reachable there if even the first action does not fit (R13). It still hides itself in a diff with no outline through its `update`, so the first place is empty only in viewers that do not use the outline. It also remains in `Diff.Conflicts.Additional.Actions`.

### Persistence

- The minimised state is `SmartDiffSettings.outlineMinimised`, stored with the plugin settings in `smartdiff.xml` and defaulting to `false`. It is not a setting on the Settings page; the header buttons and the strip are its only controls.
- The width is the splitter's own `smartdiff.outline.proportion` property, which `JBSplitter` already stores in `PropertiesComponent`. Minimising does not change the splitter's proportion, so it neither overwrites nor loses the remembered width.
- A new diff window reads both values: it starts with the last visible mode and the last width. Hiding does not change the minimised state, so the next diff still remembers it (R14).

### Edge cases

- A fallback state (no engine, unsupported, too large, failed) minimises and restores like a result with rows; the strip shows the fallback title and reason, never an empty summary line.
- Edits while minimised still rerun the comparison after the debounce, because the outline is visible; the strip's summary updates when the result arrives.
- Hiding while minimised cancels the comparison and removes the strip. The remembered minimised state stays, so turning the toggle on again returns the strip.
- Disposing the viewer removes the strip and releases the panel as before.
- The merge outline keeps its own panel and is out of scope for this feature.

## Tests

| Requirement | Check |
| --- | --- |
| R7 (issue M1) | `OutlineIntegrationTest` `test outline 02 R7 the header hide button turns the outline off for this diff window`: the Hide icon, hiding, the window's next file, and another window |
| R8, R9, R11 (issue M2) | `OutlineIntegrationTest` `test outline 02 R8 R9 R11 the minimise button shows the summary strip and restores the same result`: the strip and summary, then restoring the same `OutlineState.Shown` instance without a new comparison; `test outline 02 R9 minimising before a fallback result arrives names the fallback` and `test outline 02 R9 minimising after a fallback result names the fallback` check that unsupported, too large and failed results show their title and reason, never `No structural changes` |
| R10 (issue M2) | `OutlineIntegrationTest` `test outline 02 R10 clicking the strip restores the panel at its previous width`: a chosen splitter proportion survives minimise and restore; `test outline 02 R10 Enter restores the strip and focus is visible` checks the Enter binding and focus border |
| R12 (issue M3) | `OutlineIntegrationTest` `test outline 02 R12 the toggle has a default shortcut that does not clash and Keymap lists it`: the default keymap binds `Ctrl+Alt+Shift+O` to the toggle and to no other action |
| R13 (issue M4) | `OutlineIntegrationTest` `test outline 02 R13 the toggle is placed first in the diff toolbar`: the toolbar group's first action is the toggle |
| R14 (issue M5) | `OutlineIntegrationTest` `test outline 02 R14 the minimised state and width are remembered for the next diff`: the next file and a new window start minimised at the saved width; `SmartDiffSettingsTest` `test outline 02 R14 the minimised state is stored` |

### Manual checks

| Requirement | Steps |
| --- | --- |
| R7 | Open a Java diff with the outline. Hover the header's Hide button and check the platform Hide icon and description. Click it: the panel disappears, both editors stay, the toolbar toggle is off, and the next file in the same window starts hidden. Open another diff and check its outline is still shown. |
| R8, R9 | Open a diff with a result. Click the header's Minimise button: a thin strip with the outline icon remains at the right edge, and its tooltip shows the summary line. Minimise a diff whose analysis fails: the tooltip shows `Analysis failed` and its reason, not `No structural changes`. Check the icon's accessible name in the UI inspector if available. |
| R10, R11 | Drag the splitter to a distinctive width, minimise, and click the strip. The panel returns at that width. The rows are already there; there is no `Comparing…` state. Repeat with the keyboard: Tab to the strip and check the focus border, then press Space or Enter. |
| R12 | Open `Settings → Keymap`, search for `Structural Outline`, and check the shortcut `Ctrl+Alt+Shift+O` (`Cmd+Option+Shift+O` on macOS) is listed under SMART Diff. Press it in a diff: the outline toggles. Check another action does not have the same shortcut. |
| R13 | Narrow a diff window until the toolbar shows `>>`. Check the Structural Outline button is the first toolbar action and, when the window is too narrow for it, the button is in the `>>` overflow. |
| R14 | Minimise a diff, close it, and open another diff: it starts minimised at the same width. Restore the panel and open one more: it starts full. Restart the IDE and check the same state is remembered. |
