# Analysis fallback states: details

Status: implemented, 2026-11-04

Feature: [Analysis fallback states](05-analysis-fallback.md)

## Design

### Where it plugs in

- `OutlineController.refresh` selects an engine for the file's language. When none supports it, it shows `OutlineState.NoEngine` with the language's display name.
- `OutlinePanel.show` maps the result status to the outline: `UNSUPPORTED`, `TOO_LARGE` and `FAILED` use `showFallback`, `ANALYZED` shows the rows, and `PARTIAL` shows the rows plus the "Some source was not analyzed" banner.
- `OutlineDiffExtension` attaches the panel only to a two-sided text diff of one language. A plain-text diff without an engine, a binary diff and a comparison of two different languages are left alone, so they show the native diff only.
- `ChangesSummaryService` turns a missing summary into the Changes tree reason: `Status.TOO_LARGE` gives `not analyzed: too large`; a timeout gives `not analyzed: timed out`; a parse failure gives `not analyzed: parse errors`; every other `FAILED` result gives `not analyzed: analysis failed`. Added, deleted and unsupported files return no summary.

### State table

| State | Cause | Outline | Changes tree |
| --- | --- | --- | --- |
| No engine | The two sides are in one language and no engine supports it | Message naming the language; native diff shown | File in that language gets no summary |
| `UNSUPPORTED` | The engine reports the file as unsupported | `Not supported` with the reason; no rows | No summary |
| `TOO_LARGE` | A size or declaration limit was exceeded | `Too large to analyze` with the reason; no rows | `not analyzed: too large` |
| `FAILED` | The engine could not analyze the file, including a timeout | `Analysis failed` with the reason; no rows | `not analyzed: timed out`, `not analyzed: parse errors` or `not analyzed: analysis failed` |
| `PARTIAL` | Some declarations or the file region are unanalyzed | Rows and the banner "Some source was not analyzed; see the Unanalyzed group." | Summary of the analyzed changes |
| `ANALYZED` | The comparison succeeded | Rows | Summary and declaration rows |

The messages are one line, centered in the outline panel. The native diff is never hidden or replaced, and the SMART Diff viewer's faded changes are cleared when no engine applies, because the message promises that the native diff shows every change.

### Failure behavior

A comparison that is canceled by a timeout reaches the panel as `FAILED` with the reason `timed out`; a cancellation for another reason is not shown as a result. A fallback state never throws and never leaves an empty outline without a message. In the [Changes tree](../changes-tree/03-coverage-and-scale.md), a timed-out summary keeps its reason visible while one retry is queued after 1 s, and a second timeout is kept (changes-tree/03 R10).

## Tests

| Requirement | Check |
| --- | --- |
| R1, R2 | `OutlineIntegrationTest` `test a language without an engine shows the outline with an unsupported message`: the exact message names the language and says the native diff shows every change, and the message is the panel's accessible name |
| R3 | `OutlineIntegrationTest` `test a result over the size limit shows a status message`: `Too large to analyze`, the reason, and no outline rows |
| R4 | `OutlineIntegrationTest` `test a failed comparison shows analysis failed with the reason`: `Analysis failed`, the reason, and no outline rows |
| R5 | `OutlineIntegrationTest` `test a comparison over the time limit is canceled and shown as failed`: `Analysis failed` with `timed out` |
| R6 | `OutlineIntegrationTest` `test a partial comparison shows its rows and a banner`: the analyzed rows and the "Some source was not analyzed" banner |
| R7 | `ChangesSummaryTest` `test the summary is the outline's summary line, or the reason it was not analyzed`: `not analyzed: too large`, `timed out`, `parse errors` and `analysis failed` |
| R8 | `ChangesSummaryTest` `test added and deleted files and other languages get no summary`: added, deleted and engine-less files |
| R9, R10 | `OutlineIntegrationTest` `test every fallback keeps both editors and never claims the versions are the same`: both editors and their text stay, and the message makes no equality claim |
| R11 | `OutlineIntegrationTest` `test a language without an engine shows the outline with an unsupported message` toggles the outline in a fallback state, and `test the setting keeps the outline hidden in a fallback state` checks the setting |
| R12 | `OutlineIntegrationTest` `test an unsupported result shows not supported with the reason`: `Not supported`, the reason, and no outline rows |
