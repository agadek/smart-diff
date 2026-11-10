# Changes tree coverage and scale: details

Status: implemented, 2026-11-04

Feature: [Changes tree coverage and scale](03-coverage-and-scale.md)

## Design

### Where it plugs in

`ChangesSummaryModifier.changeNodes` walks the platform tree with `depthFirstEnumeration` and collects leaf `ChangesBrowserChangeNode`s until it has `MAX_FILES = 500`. It then asks `ChangesSummaryService` for each node's summary. A node with children, such as a changed directory, is not a file node and is left alone.

The limit applies before analysis, not after: added, deleted and unsupported nodes in the first 500 occupy a place, even though `ChangesSummaryService` returns no summary for them today. [changes-tree/06](06-added-deleted-file-summaries.md) (accepted 2026-11-10) adds a summary for added and deleted files; the limit would continue to count them. A modified file after the limit is not analyzed for the tree. This keeps the work bounded by the tree size rather than by the language of each file.

### Why 500

Tree building runs on the UI thread, and each considered file can start a background comparison. 500 file nodes is enough for a review-sized change list and bounds the work of a very large branch. Depth-first order is the order the platform builds the tree, so it is deterministic for the same tree. The limit is a constant, not a setting: changing the analysis limits in the settings changes each comparison, not the number of files.

Rebuilds use the same traversal. A file that moves in the tree, for example because a directory is collapsed or a file is renamed, can enter or leave the first 500; files that keep their place keep their summary, because `ChangesSummaryService` caches by paths, revisions and engine.

### Interaction with reasons

A considered file that is `TOO_LARGE` or `FAILED` gets a reason row instead of a summary (feature R7). A considered added or deleted file gets nothing today; [changes-tree/06](06-added-deleted-file-summaries.md) (accepted 2026-11-10) adds a summary for them. Files after the limit keep the platform's row in every case and do not get a reason either.

A considered file whose summary times out keeps `not analyzed: timed out` visible while one retry is queued after `RETRY_DELAY_MILLIS` (1 s). If the retry also times out, the reason stays and the retry is not repeated (feature R10), matching [technical specification §9.4](../../TECHNICAL-SPECIFICATION.md#94-changes-tree-summary) and `ChangesSummaryService`.

## Tests

| Requirement | Check |
| --- | --- |
| R1, R2, R3 | `ChangesSummaryTest` `test the first 500 file nodes are summarized and the rest keep the platform row`: the first 500 file nodes in depth-first order get a summary, and the next keeps the platform row with no summary and no declaration rows |
| R4 | `ChangesSummaryTest` `test a rebuild summarizes the same 500 file nodes`: two rebuilds of the same tree summarize the same files in the same order |
| R5 | Manual check: open Settings \| Tools \| SMART Diff and confirm there is no file-count limit next to the analysis limits; change `maxChars`, `maxDeclarations` and the timeout, rebuild a tree with more than 500 file nodes, and confirm the tree still summarizes at most 500 files and the same first 500 |
| R6 | `ChangesSummaryTest` `test added and deleted files and other languages get no summary` |
| R7 | `ChangesSummaryTest` `test the summary is the outline's summary line, or the reason it was not analyzed`: `TOO_LARGE`, timeout, parse-error and failed reasons |
| R8 | `ChangesSummaryTest` `test a changed file shows its summary once it is computed`: the first tree is not blocked and the summary arrives from the background |
| R9 | `ChangesSummaryTest` `test a file after the limit stays in the tree and opens like the platform's row`: the change is still in the tree and included, and the declaration action leaves the row to the platform (`handleDoubleClick` returns false) |
| R10 | `ChangesSummaryTest` `test a file over the time limit says it timed out`: one retry after the delay, then the reason is kept and not retried again |
