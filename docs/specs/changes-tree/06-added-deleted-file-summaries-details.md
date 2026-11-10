# Summaries for added and deleted files: details

Status: accepted, 2026-11-10

**Feature:** [Summaries for added and deleted files](06-added-deleted-file-summaries.md)

## Design

### Current behaviour

`ChangesSummaryService.request` returns `null` when either `Change.beforeRevision` or `Change.afterRevision` is `null`, so `summaries()` returns `null` for an added or deleted file and `ChangesSummaryModifier` leaves the platform's row unchanged. That is [changes-tree/03](03-coverage-and-scale.md) R6 and [changes-tree/01](01-changes-tree-summary.md) R4 today. The engine itself already accepts an empty side: `DeclarationEngineTest` covers `empty and one-side-empty texts are analyzed`, and one side's declarations are reported as added or removed.

### Summary request

`request` accepts a change with one side missing. The missing side is represented by an empty `CharSequence`:

- an added file compares `""` with the new side's text;
- a deleted file compares the old side's text with `""`.

The existing side is read with `content(revision)` on the summary executor, never on the EDT. If that side cannot be read, the request returns `null` and the platform row stays plain, as for a modified file whose revision cannot be read. The language and engine come from the existing side's file name, as today.

`ComparisonRequest` is unchanged. The engine's result for a one-side-empty request labels every declaration of the existing side as added or removed, and the file region follows the same classification ([declarations/02](../declarations/02-change-classification.md)).

### Summary text and rows

The summary text is the same `OutlineModel.summary(result)` used by a modified file, for example `12 added` for an added file or `12 removed` for a deleted file. It counts the same changes the outline counts, including containers and a reported file region. A file whose result is `FAILED` or `TOO_LARGE` keeps the existing reason, such as `not analyzed: too large`.

The rows are the same `OutlineModel.build(result, oldText, newText, ...)` rows, so they have the outline's nesting, groups and labels. `ChangesSummaryModifier` already wraps the platform node in `SummaryChangeNode` and adds `DeclarationNode` children; only the request needs to stop returning `null`. The tree's filter and toggles then work through the existing `ChangesTreeFilter` and `ChangesSummaryModifier` paths.

### Opening a row and the one-sided outline

`DeclarationNodeAction` already puts the row's `OutlinePath` on the platform diff request. For an added or deleted file the platform opens a [one-sided diff](../../GLOSSARY.md#one-sided-diff) (`SimpleOnesideDiffViewer`, an `OnesideTextDiffViewer`). The outline currently attaches only to `TwosideTextDiffViewer` and `ThreesideTextDiffViewer` ([outline/01](../outline/01-structural-outline.md)), so this feature extends `OutlineDiffExtension` and `OutlineController` with an `OnesideTextDiffViewer` path:

- the shown side is `viewer.side` (`Side.OLD` for a deleted file, `Side.NEW` for an added file);
- the editor is `OnesideTextDiffViewer.getEditor()`;
- the range framed is the item's `oldRange` on the old side and `newRange` on the new side;
- the panel, summary line and filter are the same; the absence label says that the other side has no declaration.

When the one-sided outline cannot attach, `DeclarationNodeAction` keeps its existing fallback: the file's diff opens without a declaration selected (changes-tree/02 R16).

### Caret follow

`ChangesCaretFollow.sideFile` already handles `OnesideDiffViewer`: it returns the shown side and the editor's file, and the existing mapping reads the old-side or new-side range. Today [changes-tree/05](05-changes-tree-caret-follow.md) R8 selects only the file row for an added or deleted file because the file has no summary. Once the summary exists, the same mapping can select the declaration under the caret. The platform's selection-following preview must still select the file row only, because a declaration row closes or empties the preview ([changes-tree/05](05-changes-tree-caret-follow.md) R3). [changes-tree/05](05-changes-tree-caret-follow.md) R8 changes when this feature is implemented.

### Limits, cache and threading

- The analysis limits apply to the side that exists: a side over 1 MB or over 5 000 declarations shows the existing reason, and the time limit and one retry behave as for a modified file ([engines/03](../engines/03-analysis-limits.md)). A large added file is therefore a one-sided analysis of the existing side, with the same 1 MB, 5 000-declaration and 5 s bounds as a modified file; its cost is bounded and the row shows the reason instead of blocking the tree.
- The 500-file limit counts added and deleted file nodes in the tree's depth-first order, like modified files ([changes-tree/03](03-coverage-and-scale.md)). A file after the limit keeps the platform row.
- The cache key adds a missing-side marker to the current key, so an added and a deleted file with the same path and version are distinct: `Key(existingPath, existingPath, version, missingSide, engineId, confidence, stamp)`, where `missingSide` is `NONE`, `OLD` or `NEW`. The missing side contributes a constant version.
- An edit to an added file changes the new side's version and gives a new key; a restored deleted file becomes a modified or moved change, not an added/deleted one.
- The summary executor reads the existing side off the EDT. The tree rebuild, filtering and row creation stay on the platform's tree-build thread, as today.

### Edge cases

- **Renamed or moved file:** both revisions exist, so the existing modified-file path runs and R9 holds.
- **Binary, unsupported language or no engine:** the file type has no language or the registry selects no engine, so there is no summary and no rows. The platform row is unchanged.
- **Empty added file:** the existing side has no declarations. The outline reports no changes; the file keeps the platform row with no summary, rather than showing an empty `0 added` line.
- **File with only a file region:** the outline's file-region row is the row; the summary counts it as added or removed, as the outline does.
- **Relationships:** one side is empty, so there is no source or target for an extraction or inlining; no relationship is created.
- **File outside the content roots:** the language and engine still come from the file name; the platform may not show it in the tree at all, and if it does, the existing-side content read applies.
- **Deleted file whose old revision is unavailable:** no summary; the platform row stays plain.
- **Large added file:** the existing side's limits apply; the row shows the reason and no rows, and the native diff stays available.

### Decisions

- **One-sided outline.** A declaration row opens the file's one-sided diff with a one-sided outline attached to `OnesideTextDiffViewer`. The shown side is framed, as R6 and R7 say. This extends the two-sided outline in [outline/01](../outline/01-structural-outline.md) to one-sided diffs; the three-sided and merge outline is [merge/03](../merge/03-three-sided-diffs.md).
- **Caret follow.** In a normal one-sided diff, the caret selects the declaration row under it; the platform's selection-following preview keeps selecting the file row, because a declaration row closes or empties the preview. [changes-tree/05](05-changes-tree-caret-follow.md) R8 changes when this feature is implemented.

## Tests

Planned tests, named by the requirement they check. The classes already exist; the methods are new unless noted.

| Requirement | Planned test |
| --- | --- |
| R1, R3, R4 | `ChangesSummaryTest` `test an added file gets a summary of its added declarations`: a Java added file with a known count has a summary with that count and `added`, the file expands, and its rows have the outline's nesting, groups and added labels. A second case asserts that a reported file region is counted. |
| R2, R3, R4 | `ChangesSummaryTest` `test a deleted file gets a summary of its removed declarations`: the deleted file has the known count with `removed` and the same nesting, groups and removed labels. |
| R1, R2 | `ChangesSummaryTest` `test an empty added file keeps the platform row`: an added file with no reported changes has no summary and no rows. |
| R5 | `ChangesTreeFilterTest` `test the tree filter and toggles apply to an added file`: hiding added removes its rows and updates the summary; Declarations off makes it a leaf; Structural Summary off hides the text; the deleted-file variant follows. |
| R6 | `OutlineIntegrationTest` `test a one-sided diff of an added file shows the outline and frames the shown side` and `test a one-sided diff of a deleted file frames the old side`: the outline attaches to `SimpleOnesideDiffViewer` and selecting a row frames the side that exists. |
| R7 | `ChangesSummaryTest` `test a declaration row of an added or deleted file falls back to the file diff`: with the one-sided outline unavailable, the row opens the file's diff without a selected declaration. |
| R8 | `ChangesCaretFollowTest` `test a one-sided diff of an added file selects the declaration under the caret`, `test a one-sided diff of a deleted file selects the declaration under the caret` and `test the platform preview of an added file selects the file row`. |
| R9 | `ChangesSummaryTest` `test a renamed file keeps the modified-file summary` and `test a moved file keeps the modified-file summary`: both revisions exist, so the summary is not the added or deleted form. |
| R10 | `ChangesSummaryTest` `test a binary or unsupported added file has no summary`; the manual check repeats it for a deleted file. |
| R11 | `ChangesSummaryTest` `test a too-large added file shows the reason`: the row shows `not analyzed: too large` and has no rows; the manual check repeats it for a deleted file. |
| R12 | `ChangesSummaryTest` `test added and deleted files count toward the 500-file limit`: a file after the first 500 keeps the platform row. |
| R13 | `ChangesSummaryTest` `test an added file's summary is computed off the EDT`: a revision stub fails if its content is read on the EDT; the deleted-file variant uses the old side. |
| R14 | `ChangesSummaryTest` `test an edit to an added file gives it a new summary`: a changed new-side version is a new cache key; an added and a deleted file at the same path do not share an entry. |
| R15 | The existing tree tests cover Local Changes and the Commit tool window; the modal commit dialog and Git log are out of scope. |

Manual checks:

- In IC 2025.1.3 and IU 2026.3, with a real Git project: add a Java, Kotlin, Scala 2 and Scala 3 file and delete one of each; confirm `N added`/`N removed`, expand the rows, and check the labels.
- Open a declaration row; the one-sided diff opens and frames the declaration on the side that exists. Check the fallback by opening a row whose one-sided diff cannot be created.
- Move the caret through the one-sided diff; the declaration under the caret is selected, and the platform preview selects only the file row.
- Apply the tree filter and both toggles to an added and a deleted file; check the hidden counts and `all hidden`.
- Add a binary, an unsupported file and a too-large file; confirm no summary or the reason, and that the native diff stays.
- Put more than 500 file nodes in the tree and confirm the added/deleted nodes after the limit keep the platform row.


