# Analysis fallback states: details

Status: implemented, 2026-11-01

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

A comparison that is canceled by a timeout reaches the panel as `FAILED` with the reason `timed out`; a cancellation for another reason is not shown as a result. A fallback state never throws and never leaves an empty outline without a message.

## Tests

| Requirement | Check |
| --- | --- |
| R1, R2 | `OutlineIntegrationTest` opens a YAML diff with no engine and checks the message; the message is part of the panel's accessible name |
| R3, R4 | `OutlineIntegrationTest` checks the size and time limits; `OutlineModelTest` checks the fallback states without the IDE |
| R5 | `OutlineIntegrationTest` timeout case |
| R6 | `OutlineModelTest` `PARTIAL` result and banner |
| R7, R8 | `ChangesSummaryTest` checks the four reasons, and that added, deleted, unsupported and other-language files get none |
| R9, R10 | `OutlineIntegrationTest` checks that the native diff stays and that the viewer still hides no lines; product-scope P2 is checked in the label tests |
| R11 | `OutlineIntegrationTest` toggles the outline in a fallback state |
| R12 | `OutlineModelTest` `UNSUPPORTED` state |
