# Changes tree caret follow: details

Status: implemented, 2026-11-07

Feature: [Changes tree caret follow](05-changes-tree-caret-follow.md)

## Design

### Where it plugs in

`ChangesTreeCaretFollower`, a project service in `ui/changes/ChangesCaretFollow.kt`, is created when the project opens by
a `ProjectActivity` registered in `smartdiff-vcs.xml`, and is disposed with the project. It listens to:

- `EditorFactory.getInstance().eventMulticaster.addCaretListener(...)`, for a caret move in any editor; editors of other
  projects are ignored.
- `FileEditorManagerListener.selectionChanged`, so switching to an editor also follows without a caret move.
- `ChangesViewModifier.TOPIC`, to mark that a summary rebuild is coming, and the live tree's `TreeModelListener` plus
  its model property change, to retry once the rebuilt model is installed.
- `SmartDiffSettings.CHANGED`, for the toggle.

It finds the live tree through the public `ToolWindowManager` and the `Version Control` and `Commit` tool windows, the
same way `ChangesTreeSetup` reaches the tree: walk each content's components and take the first `ChangesListView`. This is
the same tree in Local Changes and the Commit tool window and avoids the VCS implementation class, which moved modules
between 2025.1 and 2026.3. `ChangesViewManager.selectFile` is not enough because it selects only the file's row. The
service follows only while the tree is showing; a closed tool window or another tab is left alone. If the tree is not
there, the service does nothing; the next caret move after it opens follows.

The service never wraps or replaces the tree, its renderer or its model. It only reads the live nodes and changes their
selection and expansion through `JTree`.

### Data

The service adds no persistent data. It reuses the tree's own nodes and the summary's rows:

- `SummaryChangeNode` is the file row with a summary; `ChangesBrowserChangeNode` is a file row without one, such as an
  added, deleted or unsummarized file.
- `DeclarationNode.item` is the `OutlineItem`, with `oldRange`, `newRange` and `children`.
- `DeclarationNode.path` is the row's `OutlinePath`, built from its `OutlineItem`s.

`ChangesSummaryService` gains a read-only lookup for one change:

```kotlin
sealed interface SummarySnapshot {
    data class Fresh(val summary: FileSummary) : SummarySnapshot
    data object Stale : SummarySnapshot   // an older summary is still in the cache
    data object Pending : SummarySnapshot // the current summary is being computed
    data object None : SummarySnapshot    // no summary for this file
}

fun snapshot(change: Change): SummarySnapshot
```

`snapshot` builds the same `Key` as `summaries` (both paths, a version of each side, the engine and the confidence policy)
but starts no comparison, does not evict other entries and does not read revision content. It only looks in the cache and
compares the current key with the entries for the same paths:

- `Fresh`: the current key has a `Done` entry. A summary with a reason or no rows is still `Fresh`; the caller falls back
  to the file row.
- `Stale`: the current key is absent or pending while an entry for the same paths has another key. This is the state
  after an edit and before the tree rebuild. Following keeps the current selection and retries on the rebuild.
- `Pending`: the current key is `Pending` and there is no older entry. Following selects the file row until the summary
  arrives, then follows again on the rebuild.
- `None`: there is no entry for the paths. This covers added and deleted files, a language without an engine, the summary
  setting being off and a file after the 500-file limit. Following selects the file row.

The version in the key is already computed from the revision number, or for a local file from the cached document's or
file's modification stamp. Reading it is cheap and does not load revision content, so `snapshot` is safe on the EDT. The
cache is a `ConcurrentHashMap`, so the read is safe while the background executor stores a result.

### From caret to row

All of this runs on the EDT, except the summary comparison that already runs in the background.

1. **Take the editor and the offset.** A caret event gives the editor and its caret offset. A file-editor selection
   change uses the selected text editor and its current caret offset.
2. **Find the side and the file.**
   - Read `DiffDataKeys.DIFF_VIEWER` from the editor's data context. If a viewer is present and is not a
     `TwosideTextDiffViewer`, return without touching the tree; a three-sided diff or merge window is out of scope.
   - If no viewer is present, treat the editor as a plain editor. Otherwise use the `TwosideTextDiffViewer`.
   - If `viewer.editor2 === editor`, the side is the new side and the file is `viewer.content2.highlightFile`.
   - If `viewer.editor1 === editor`, the side is the old side and the file is `viewer.content1.highlightFile`.
   - Otherwise the editor is a plain editor: the side is the new side and the file is
     `FileDocumentManager.getInstance().getFile(editor.document)` or `editor.virtualFile`.
   - If the side's content names no file, the other content's `highlightFile` is used to find the change, while the side
     still decides which range is used. This covers a renamed file whose old side has no current file. If neither content
     names a file, the event is ignored.
3. **Find the change and its row.** Walk the live tree's file nodes in tree order and take the first whose `Change` has a
   matching side path: `afterRevision.file` on the new side, `beforeRevision.file` on the old side, compared by path and
   directory kind, since a content may hold a different `FilePath` implementation. This also handles a moved or renamed
   file, whose two paths differ. If no row is there, nothing is selected.
4. **Check the summary.** If the row is not a `SummaryChangeNode`, or `snapshot` is `None` or `Pending`, select the file
   row. If it is `Stale`, keep the current selection and retry on the rebuild. A `Fresh` summary with no `items` also falls
   back to the file row.
5. **Find the innermost changed declaration.** Walk the summary's `OutlineItem` tree, from the top level down, with the
   source offset. A row is a candidate only when it is a change itself (`kind == ItemKind.CHANGE` and `tags.isNotEmpty()`);
   groups, candidates, container-only rows and the `File region` row are not. A row contains the caret when its side range
   contains the offset: `item.newRange` on the new side, `item.oldRange` on the old side; a null range does not contain.
   The check is `offset in range`, the same half-open check as `TextRange.contains`, so a zero-length range contains no
   offset. Recurse through all children, including a container that is not a candidate, so a changed method inside an
   unchanged class is found. Keep the deepest candidate on the way down; at the same depth, the first row in outline order
   wins. With no candidate, fall back to the file row.
6. **Select and expand.** Build the target row's `OutlinePath` from the rows on the path. Under the file node, find the
   `DeclarationNode` whose `path` has the same steps (the same kind and title at each level, groups matched by kind as
   `OutlinePath.find` does). Expand every row above the target with `JTree.expandPath`, set `JTree.selectionPath` and
   call `JTree.scrollPathToVisible`; `TreeUtil.selectPath` only scrolls to a path whose rows already have bounds, so the
   service expands the ancestors first. For the file row, do the same with its path. If the declaration row cannot be
   found, for example because the tree has not rebuilt yet, keep the current selection and remember the target; the model
   listener retries after the rebuild. If the rebuilt tree still has no such row, select the file row. If the tree is
   updating its model, do nothing; the model listener retries. Wrap the selection in a `following` flag so the tree's own
   selection listener does not treat it as a user selection.
7. **Use the shown rows.** The tree's filter and its Declarations toggle decide which rows exist. When the declaration
   under the caret is hidden, take the deepest shown row on its path, including the target's own row when the filter keeps
   it as a container without labels, as `OutlinePath.find` does; that is the nearest shown row above the hidden change,
   the same fallback as changes-tree/02 R12. If no row on the path is shown, select the file row. With Declarations off
   there are no declaration rows, so the file row is selected (R10).

The file row fallback covers R2, R8 and R10: the caret is outside every changed declaration, the file has no summary, the
summary says it was not analyzed, the language has no engine, the file is added or deleted, the file is after the
500-file limit, or the tree's filter hidden every row on the caret's path. A file row that is not in the tree is left alone.

### Staleness, rebuilds and the tree's own selection

- On an edit, the current summary becomes stale. A caret event during that time does not change the tree's selection:
  `snapshot` returns `Stale`, and the service remembers the editor, side and caret offset, not the old row path.
  `ChangesViewModifier.TOPIC` marks that a rebuild is coming; when the tree's model changes, the service maps the
  remembered caret against the new summary again. The model listener is attached to the current `ChangesListView.model`
  and reattached when the model property changes, so the retry runs against the rebuilt tree, not the old one. The retry
  goes through the same 100 ms timer, so a burst of model events follows once.
  If the new summary still contains the row, it is selected; if it does not, the file row is selected. While the summary
  is stale the follower never picks a row from it. R7 is about what the follower chooses, not about preserving a row
  through a platform rebuild: if an unrelated rebuild removes the old row first, the platform's own selection restoration
  applies, and the follower retries when the new summary arrives. A stale summary therefore never makes the follower
  select a row at an offset from the old text.
- A rebuild restores the user's expanded rows through the platform. Following after a rebuild re-expands only the rows it
  needs to show the target.
- The service installs one `TreeSelectionListener` and one mouse/key listener on the live tree. A mouse press or key press
  in the tree cancels a pending follow at once and increments a selection epoch; a selection change that did not come from
  the follower while the tree has focus does the same. A follow task captures the epoch; if the epoch changed before it
  runs, it does nothing. The remembered target from a stale summary carries that epoch too, so a click or key press in the
  tree drops it. This keeps a late caret event from overwriting a row the user just clicked. A tree selection made by the
  follower sets `following`, so it does not bump the epoch.
- If the tree has focus when the follow task runs, it does nothing. If another editor is focused, the target is stale and
  is dropped. A later caret move in the editor follows again.
- If the setting is turned off, a pending follow is cancelled and the tree is left as it is. Turning it on does not move
  the tree until the next caret or editor-selection event.

### Setting

The platform has no "Select Opened File" or autoscroll-from-source setting for the Changes view in 2025.1 or the latest
release. Its `SelectInChangesViewTarget` serves the manual Select In action only, and the Project view's "Always Select
Opened File" belongs to a different tree. The feature therefore gets its own application setting,
`followChangesTreeCaret`, in `SmartDiffSettings.SettingsState`, copied in `update` and shown as the checkbox "Follow the
caret in the Changes tree" in `SmartDiffConfigurable`. It is on by default because it does not fight any platform
behavior for this tree. The follow toggle is display-only on both sides: changing it does not call
`ChangesSummaryService.clear()` and does not publish `ChangesViewModifier.TOPIC`, so it neither clears the cache nor
rebuilds the tree. The summary settings listener ignores a change that only touches `followChangesTreeCaret`. For all
other settings it follows the rule in §9.4: a display toggle of the tree, including Show structural summary, or its
filter only rebuilds and keeps the cache, while an analysis setting (engine, limits or confidence policy) or the
formatting-only layout, which changes the cached rows, clears the cache and recomputes the summaries (settings/01
R13–R15). Turning the follow toggle off only cancels a pending follow and leaves the tree as it is.

### Debounce

Caret and editor-selection events restart a `javax.swing.Timer` of 100 ms on the EDT. The timer fires once after the
last event. The value matches the platform's `ide.autoscroll.from.source.delay`, which is the feel users already know from
autoscroll from source, and it coalesces typing and held arrow keys. No comparison is started by the timer; it only reads
the cached summary and changes the tree selection.

### No focus, inclusion or diff side effects

- The service calls no `ToolWindow.activate`, `IdeFocusManager.requestFocus`, `EditorFocusManager` or `DiffManager`
  method. `JTree` selection does not take focus, so the caret and the editor's focus stay where
  they are (R5).
- Declaration rows are helper nodes (changes-tree/02), so selecting one selects no change. Selecting the file row is a
  tree selection, not a checkbox change, so the commit's inclusion and count stay the same (R5). The test asserts the
  included changes and the commit count before and after following, not the selected row.
- The follower opens no diff itself. If the platform's own single-click diff preview is on, selecting a file row may show
  that preview exactly as a click would; with the preview off, no diff opens. This is the platform reacting to a selection,
  not the follower opening a diff.

### No EDT revision reads and only public API

- The mapping uses the cached `FileSummary` and its `OutlineItem` ranges. It never calls `ContentRevision.content` or
  `FileDocumentManager.getDocument(...).text` on the EDT; the only document access is the modification stamp used by
  `ChangesSummaryService.version` for staleness.
- The tree is reached through public `ToolWindowManager`, `ChangesListView` and `JTree` API. The diff side is read
  through public `DiffDataKeys.DIFF_VIEWER`, `TwosideTextDiffViewer`, `DocumentContent.highlightFile` and
  `FileDocumentManager`.
- The service adds no reflection and no `@ApiStatus.Internal`, `@ApiStatus.Experimental` or deprecated member. The
  existing experimental usages are unchanged: the `changesViewNodeAction` extension that opens a declaration and the
  Scala provider's `PsiNamedElementWithCustomPresentation` reference. Plugin Verifier must report no new usage from this
  feature (R9).

### Failure behaviour

If there is no live tree, no matching file row, no summary or no public way to identify the side, the service does nothing
and the editor keeps working. A later caret move, editor switch or tree rebuild tries again. If a rebuilt tree still lacks
the remembered declaration row, the service selects the file row so the tree does not stay on a row that is gone. While
the tool window is closed, or another tab of it is selected, the tree is not showing, so the service does nothing; the
next caret move after the tree opens follows. Errors from the mapping are logged once and do not show a dialog; the tree
keeps its selection.

### Thresholds and limits

| Limit | Value | Reason |
| --- | --- | --- |
| Caret debounce | 100 ms | Matches the platform's autoscroll-from-source delay and coalesces typing and held arrow keys |
| Declaration search | The file's cached outline rows only | No new analysis and no work proportional to the project; files without a live tree row are skipped |
| Range boundary | `offset in range`, half-open | Same check as `TextRange.contains`; adjacent declarations do not both contain a caret at their shared boundary, and a zero-length range contains no offset |
| Summary freshness | Exact cache key match | A summary for another document version must not select a row at a stale offset |

## Tests

The new test class is `ChangesCaretFollowTest` in `src/test/kotlin/.../ui/changes/`. It builds the
tree with the platform's `TreeModelBuilder` as `ChangesSummaryTest` does, and uses a recording `DiffManager` and a
recording focus owner to check the side effects. It also opens real two-sided and three-sided diff panels through
`DiffManager.createRequestPanel`, as `OutlineIntegrationTest` does, for the side mapping.

The test seam is internal to the plugin: a `@TestOnly` `followNow(editor, offset)` method runs the same mapping and
selection path as the debounce timer, and `waitForPendingFollow()` drains the timer and any queued model retry. The test
installs the live `ChangesListView`, `ChangesSummaryService`, focus owner and diff viewer through package-private
constructor parameters, while production finds the tree through `ToolWindowManager` and the viewer through
`DiffDataKeys.DIFF_VIEWER`. This keeps global caret events, focus and timer delays out of the assertions.

The fixtures are the `ChangesSummaryTest` Cart text (a class with a changed method and an added method), a file with
unchanged code between changes, a changed class header, an added file, a deleted file, a plain-text file in an unsupported
language, a file over the size limit and a file with a timed-out or failed summary.

| Requirement | Check |
| --- | --- |
| R1 | `ChangesCaretFollowTest` `test the innermost changed declaration under the caret is selected and the file expands`: a caret in a changed method selects the method, not its class; a caret in a changed class outside its methods selects the class; the file row is expanded and the row is visible |
| R2 | `ChangesCaretFollowTest` `test the caret outside every changed declaration selects the file row`: a caret in unchanged code of a changed file selects the file row and no declaration row |
| R3 | `ChangesCaretFollowTest` `test a plain editor maps to the new side range`; `ChangesCaretFollowTest` `test the old and new sides of a two-sided diff map to their own ranges`; `ChangesCaretFollowTest` `test the caret in a three-sided diff does not move the Changes tree` |
| R4 | `SmartDiffSettingsTest` `test defaults` (settings/01 R13, the new setting is on); `ChangesCaretFollowTest` `test the setting turns following off` (settings/01 R15): with the setting off, a caret event leaves the tree selection unchanged; `ChangesCaretFollowTest` `test changing only the follow setting neither clears the summary cache nor rebuilds the tree` (settings/01 R14) |
| R5 | `ChangesCaretFollowTest` `test following leaves focus and commit inclusion alone with the platform preview off and on`: the editor keeps focus, the included changes and the commit count are unchanged, with preview off the recording `DiffManager` receives no request, and with preview on only the platform's own preview may open, as for a click |
| R6 | `ChangesCaretFollowTest` `test following leaves the tree alone while the tree has focus`; `ChangesCaretFollowTest` `test a tree selection is not overwritten by a late caret event`: click a row while a caret follow is pending, then let the timer fire; the clicked row stays; `ChangesCaretFollowTest` `test caret moves are debounced`: several events in a row produce one follow after the delay |
| R7 | `ChangesCaretFollowTest` `test a stale summary leaves the current row and selects the caret row after the rebuild`: after an edit, a caret event before the rebuild leaves the row selected; after the rebuild the row under the caret is selected |
| R8 | `ChangesCaretFollowTest` `test a file without a summary selects only the file row` (added, deleted and unsupported files); `test a pending file selects only the file row`; `test a not analyzed file selects only the file row`; `test a file after the 500-file limit selects only its file row` |
| R9 | `ChangesCaretFollowTest` `test following reads no revision content on the UI thread`: a `ContentRevision` whose `content` fails when read on the EDT is never read; Plugin Verifier report: no new internal, experimental or deprecated usage from the follower, and the existing usages stay unchanged |
| R10 | `ChangesCaretFollowTest` `test the tree filter hides the caret row and selects the nearest shown row above`: with the changed method's own label hidden, the shown class container or file row is selected, and with Declarations off the file row is selected |
| R1–R10 | Manual check: run the steps below on IntelliJ IDEA 2025.1 and the latest release |

### Manual check

Run this on IntelliJ IDEA 2025.1 and the latest release, in the Local Changes view and in the Commit tool window:

1. Open a modified Java file with at least two changed methods. Move the caret through each changed method, through
   unchanged code in the same file, and into a changed class header. The tree expands the file and selects the method,
   then the file row, then the class row.
2. Open the file's two-sided diff and repeat in the new and old editors. Each side selects the row for its own range.
   Open a three-sided diff or merge window and move the caret in each pane; the Changes tree does not move.
3. Hold an arrow key and type quickly. The tree follows after the pause, not on every key.
4. Click another row in the tree while a caret follow is pending. The clicked row stays selected.
5. Edit a changed method and move the caret before the summary finishes. The old row stays selected until the new summary
   arrives, then the row under the caret is selected.
6. Open an added file, a file in an unsupported language, a file over the size limit, a file after the 500-file limit and
   the old side of a deleted file's diff. Only the file row is selected, and the tree shows no declaration row.
7. Turn "Follow the caret in the Changes tree" off and move the caret. The tree does not change. Turn it on and move the
   caret again; it follows. Confirm that toggling it does not clear the summaries or rebuild the tree.
8. Repeat with the platform's single-click diff preview off and on. With it off, no diff opens. With it on, only the
   platform's own preview follows the selection, as it does for a click; the follower opens no diff itself.
9. Throughout, the caret stays in the editor, and the file checkboxes and the commit count do not change.

The toggle stays on the SMART Diff settings page (R4); adding it to the Changes view's View Options menu later would not
change R4. The `File region` row is not followable: changed source outside declarations selects the file row (R2), which
is less surprising in a plain editor. A closed tool window is caught up on the next caret move after it opens.
