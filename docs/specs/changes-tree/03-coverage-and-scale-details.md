# Changes tree coverage and scale: details

Status: implemented, 2026-11-01

Feature: [Changes tree coverage and scale](03-coverage-and-scale.md)

## Design

### Where it plugs in

`ChangesSummaryModifier.changeNodes` walks the platform tree with `depthFirstEnumeration` and collects leaf `ChangesBrowserChangeNode`s until it has `MAX_FILES = 500`. It then asks `ChangesSummaryService` for each node's summary. A node with children, such as a changed directory, is not a file node and is left alone.

The limit applies before analysis, not after: added, deleted and unsupported nodes in the first 500 occupy a place, even though `ChangesSummaryService` returns no summary for them. A modified file after the limit is not analyzed for the tree. This keeps the work bounded by the tree size rather than by the language of each file.

### Why 500

Tree building runs on the UI thread, and each considered file can start a background comparison. 500 file nodes is enough for a review-sized change list and bounds the work of a very large branch. Depth-first order is the order the platform builds the tree, so it is deterministic for the same tree. The limit is a constant, not a setting: changing the analysis limits in the settings changes each comparison, not the number of files.

Rebuilds use the same traversal. A file that moves in the tree, for example because a directory is collapsed or a file is renamed, can enter or leave the first 500; files that keep their place keep their summary, because `ChangesSummaryService` caches by paths, revisions and engine.

### Interaction with reasons

A considered file that is `TOO_LARGE` or `FAILED` gets a reason row instead of a summary (feature R7). A considered added or deleted file gets nothing. Files after the limit keep the platform's row in every case and do not get a reason either.

## Tests

| Requirement | Check |
| --- | --- |
| R1, R2, R3 | Manual check on a tree with more than 500 file nodes; no automated test yet |
| R4 | Manual check: rebuild the same tree twice and compare the summarized files |
| R5 | The settings page has no limit setting; changing the analysis limits leaves the 500-file count unchanged |
| R6 | `ChangesSummaryTest` added and deleted cases |
| R7 | `ChangesSummaryTest` checks `TOO_LARGE`, timeout, parse-error and failed reasons |
| R8 | `ChangesSummaryTest` waits for background summaries and checks that the tree is not blocked |
| R9 | Manual check: select, include and open a file after the limit |
