# Reviewed marks: details

Status: implemented, 2026-11-16

**Feature:** [Reviewed marks](12-reviewed-marks.md)

## Design

### What can be marked (R1, R3, R6)

A mark belongs to a `ChangeNode` of the shown result, never to an outline row, so the container tree, the refactorings view and the filter all read the same marks.

- **Markable nodes:** the nodes the summary line counts, that is every node with `isChangedItself` or grouped usages, plus the file region when it is reported. Uncertain nodes are markable; they stay in the Uncertain group. Unanalyzed ranges, candidate rows, evidence rows and usage rows have no node and cannot be marked; the actions are disabled on them.
- **M in `N of M reviewed`** is the number of markable nodes in the result, counted with hidden ones, so it does not change with the filter. N is how many of them are reviewed.
- **A row's nodes:** a `CHANGE` or `FILE_REGION` row stands for its own node, if markable, and every markable node under it. A group row (`Formatting-only (3)`, `Uncertain (2)`) and a `SECTION` row stand for the nodes of their rows. A `RELATIONSHIP` row stands for the nodes of every endpoint it frames (R13). Marking a row marks all of them; unmarking unmarks all of them (R3).
- `OutlineItem` gets `node: ChangeNode?`, set by `OutlineModel.Builder` and `RefactoringsModel` for rows built from a node, and `endpoints: List<ChangeNode>` for relationship rows, so the actions can find the nodes without matching titles.

### Identity and fingerprint (R8, R9, R10)

A stored mark is `(fileKey, path, fingerprint, markedAt)`.

- **fileKey:** the controller's `file`, as a path relative to the project's base directory, using `/` separators. When `file` is null or outside the project, the diff has no file key, and its marks live only in the controller and go away with it (R10).
- **path:** the node's declaration path in the result: the titles from `OutlineModel.title(subject)` of the node and its ancestor nodes, top down, joined by `/`. The file region's path is `File region`. A removed declaration and an added declaration with the same title have the same path; the fingerprint tells them apart.
- **fingerprint:** SHA-256, hex, of the node's old-side text, a `\u0000`, and its new-side text, each taken from the request's `oldText` and `newText` by `oldRange` and `newRange`; a missing side contributes `-`. The text is exact, so a reformat is a new change and drops the mark.
- **Applying marks:** when an outline compares the file and shows a newly compared result, after an open, a refresh or an edit, `ReviewedMarks.apply(fileKey, result, request, whitespacePolicy, dropStale)` looks up the stored marks of the file by path. A node is reviewed when a mark with its path has its fingerprint. With `dropStale = true`, a mark is dropped when the outline compares the file and shows the declaration with other text on either side (R8), so editing a declaration and editing it back does not bring the mark back, and the resulting event rebuilds every open outline of the file. A rebuild from the kept result, after a marks-changed event or a filter, view or display-setting change, passes `dropStale = false`: it only reads the marks and never drops or publishes one. So when two open outlines of the same file show a declaration with different text, marking it in one does not drop it in the other while that outline only rebuilds. A mark whose path matches no node is kept, since another diff of the same file may show the declaration again.

### Storage (R9, R15)

- `ReviewedMarks` is a project service, a `PersistentStateComponent` stored in `StoragePathMacros.WORKSPACE_FILE`, so marks stay on this machine and out of version control (P1).
- It keeps at most 10 000 marks per project. When a new mark would pass the limit, the oldest by `markedAt` are dropped first. 10 000 marks are about 1.5 MB in the workspace file, and no realistic review session reaches the limit.
- It publishes `ReviewedMarks.CHANGED` on the project message bus with the file key whenever a mark is added or removed. Each `OutlineController` of that file key rebuilds its rows from the kept result, as the filter does today, keeping the selection by `OutlinePath` (R14). Controllers without a file key keep their marks in a local set and rebuild only themselves.

### Rows and summary (R4, R5, R6, R7, R12)

- `OutlineModel.build` and `RefactoringsModel.build` take `reviewed: Set<ChangeNode>`, default empty. A row whose nodes (as defined above) are all reviewed and not empty gets `reviewed = true`.
- `appendItem` draws a reviewed row's title with `SimpleTextAttributes.GRAYED_ATTRIBUTES` and appends `reviewed` after the labels, note and confidence, in grey, with the same `, ` separator. A container shown only for its children has no labels, so it shows `reviewed` alone when R5 holds. `OutlineItem.label`, which is the accessible name, gains `reviewed` too, so the mark does not rely on grey alone (P8). The Changes tree calls `appendItem` with rows that are never reviewed, so it is unchanged.
- `OutlineModel.summary` takes the same set and adds `N of M reviewed` as the last part, after `N hidden` and before the analysis note, only when N is above 0 (R6, R7).
- **Hide Reviewed** is a `DumbAwareToggleAction` at the end of the change type filter menu, after a separator, bound to `SmartDiffSettings.hideReviewed: Boolean = false`. With it on, `isHidden` treats a reviewed node as hidden, with the same container rules as a hidden type; reviewed nodes count in `N hidden`. The setting is a display setting for outline/10 R5. It is not on the settings page.

### Actions (R2, R11, R14)

- An `ActionGroup` installed on the outline tree with `PopupHandler.installPopupMenu` holds "Mark as Reviewed", "Unmark Reviewed" and, after a separator, "Clear Reviewed Marks". Mark is enabled when the selected row has an unreviewed node, Unmark when it has a reviewed one, Clear when the file has any mark.
- Space in the outline tree toggles the selected row: when every node of the row is reviewed it unmarks them, otherwise it marks them. The key listener that handles Enter handles `VK_SPACE` too and consumes it. The outline tree has no speed search, so Space has no other meaning there.
- Marking calls `ReviewedMarks.mark(fileKey, nodes)` with each node's path and fingerprint computed from the shown request; nothing compares again (R14).

### Edge cases

- **Live edits in Local Changes:** after an edit the outline compares again; the edited declaration gets a new fingerprint and loses its mark, and every other mark stays (R8, R9).
- **Two outlines of the same file, different text:** a Local Changes diff and a commit or history diff can share a file key while showing a declaration with different text. Marking it in one outline does not drop the mark: the other outline only rebuilds from its kept result and shows the row unmarked (R8, R14). When that other outline compares the file again after a refresh or an edit, the mark is dropped (R8).
- **Same title twice:** two overloads with the same title and different signatures already have different titles. Two nodes with the same path and the same fingerprint, such as two identical uncertain candidates, share a mark; marking one marks both. This is rare and errs towards showing both as reviewed only when their text is identical.
- **Renamed file:** the file key changes, so its marks do not follow the rename.
- **Failure:** if reading or writing the workspace state fails, `ReviewedMarks` keeps the marks in memory for the session and logs a warning once; the outline still works.

## Tests

| Test | Checks |
| --- | --- |
| `ReviewedMarksTest`: mark and unmark, fingerprint match and mismatch, mismatch deletes the mark, a missing path keeps it, the 10 000 limit drops the oldest, state round trip, no file key keeps marks local | R1, R8, R9, R10 |
| `ReviewedOutlineModelTest`: label and accessible name, greyed title, container rule, group and parent marking, `N of M reviewed` with hidden nodes, no change without marks, Hide Reviewed with containers and the hidden count, unmarkable rows, the refactorings view's relationship and other-changes rows | R3, R4, R5, R6, R7, R12, R13 |
| `OutlineIntegrationTest` outline 12: the context menu and Space mark and unmark without a new comparison and keep the selection; Clear Reviewed Marks; a second outline of the same file updates; an edit to one declaration drops only its mark; marks survive closing and reopening the diff | R2, R11, R14, R8, R9 |
| `SmartDiffSettingsTest`: `hideReviewed` off by default, persisted, counted as a display setting | R12 |
| `ChangesTreeFilterTest`: the Changes tree draws no `reviewed` label | Out of scope |

The relationship benchmark and the corpus validation must give the same output as before, since classification does not change (R15).
