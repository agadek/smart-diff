# Moved lines in unsupported files: details

Status: implemented, 2026-11-16

**Feature:** [Moved lines in unsupported files](04-moved-lines.md)

## Design

### Where it plugs in

The [line comparison](../../GLOSSARY.md#line-comparison) is a `ComparisonEngine`, `LineEngine` in a new package `io.github.agadek.smartdiff.engine.lines`, with id `smartdiff.lines`, display name `Lines` and its own version string. It is **not** registered on the `comparisonEngine` extension point. `EngineRegistry`, `EngineSelection`, the engine dropdown, the settings page's "Default Comparison Engine" table, `ChangesSummary`, `MergeWindowSupport` and the merge outline therefore never see it (R4, R17, R19), and no stored default can name it.

`LineEngine.supports(languageId)` returns true for every language id; the UI decides when to use it. The outline cannot construct the engine directly, because the architecture rule in [TECHNICAL-SPECIFICATION §2](../../TECHNICAL-SPECIFICATION.md#2-architecture) forbids `ui` to depend on a concrete engine package. It therefore asks the `LineEngineProvider` application service in `engine.api`, implemented by `LineEngine`, which keeps the engine off the extension point and out of the registry.

The UI uses it in one place, `OutlineController`, through a new method `engineFor(selection: EngineSelection): ComparisonEngine?`:

1. `selection.selected` when it is not null (R4).
2. Otherwise `LineEngine` when the setting is on (R1, R2) and the controller belongs to a two-sided diff (R19).
3. Otherwise null, which keeps today's `OutlineState.NoEngine` message ([outline/05](../outline/05-analysis-fallback.md) R1).

`refresh()` and the staleness check in `finish()` both call `engineFor(selection())`, so a result is dropped when the engine that would be chosen now differs from the job's engine, for example after the setting changes. `OutlineController` gets a constructor flag `lineComparison: Boolean`, true only from `OutlineDiffExtension.attachTwoSided`; `attachOneSided` passes false.

`OutlineDiffExtension.attachTwoSided` today returns early for plain text without a selected engine. It now returns early only when, in addition, the setting is off (R1, R3). `attachOneSided` keeps its plain-text check unchanged, and `attachThreeSided` is unchanged (R19).

The request is built as for any engine, and `compareSafely` applies the size check and the time limit as it does for every engine (R18).

### The setting

`SmartDiffSettings.compareLinesWithoutEngine: Boolean = true`, shown on the settings page as "Show moved lines in files without an engine" ([settings/01](../settings/01-settings-page.md) R21). Applying a change notifies open outlines through the existing settings listener, which calls `refresh()` (R21). A plain-text diff that was opened while the setting was off has no outline panel; it gets one the next time a diff is opened, which is how the other per-diff panel choices behave too.

### The algorithm

`LineEngine.compare(request, cancellation)`:

1. **Lines.** Split each side into lines. A line's range covers its text and its line separator (`\n`, `\r\n` or `\r`); the last line may have none. A line's *key* is its text with leading and trailing whitespace removed (R6).
2. **Native line diff.** Run the platform's line diff, `ComparisonManager.getInstance().compareLines(old, new, ComparisonPolicy.DEFAULT, indicator)`, with an indicator whose `checkCanceled` calls `cancellation.checkCanceled()`. Its `LineFragment`s are the hunks the native diff shows, so moved blocks line up with what the user sees (R5). Lines inside a fragment's old range are *removed lines*; lines inside its new range are *added lines*.
3. **Candidates.** Index the added lines by key. For each removed line and each added line with the same key, extend forward while both next lines are removed and added respectively, are consecutive line numbers on their side, and have equal keys. Each maximal run `(oldStart, newStart, length)` is a candidate. A run may cross fragment boundaries only when the lines are consecutive in the file, which they are not across an unchanged line, so in practice a candidate lies in one fragment on each side. A candidate inside a single fragment, such as two lines swapped within one hunk, is allowed.
4. **Thresholds.** A candidate qualifies when it has at least 3 lines and its keys hold at least 30 characters that are not whitespace (R5). Three lines and 30 characters keep closing braces, blank lines, `END;` and short repeated lines from forming blocks; both values are constants in `LineEngine` and are checked by the fixtures.
5. **Uniqueness.** A candidate's key sequence must occur exactly once as a run among the removed lines and exactly once among the added lines; otherwise it is dropped (R7). This is the conservative rule of [ADR 0003](../../adr/0003-conservative-classification.md): no forced pair when the text could belong to two places.
6. **Selection.** Sort candidates by length, longest first, then by the distance between their old and new start lines, smallest first, then by `newStart`. Take each candidate whose lines are all still unused on both sides. A candidate that overlaps a taken block is trimmed to its longest part whose lines are unused on both sides, checked again against steps 4 and 5, and put back in order (R8).
7. Call `cancellation.checkCanceled()` at least once per candidate and once per fragment.

### The result

- `changes`: one `ChangeNode` per moved block, sorted by the new side's start (R9), with `subject = Subject.Region("lines A–B → C–D")` (old lines first, 1-based, an en dash between the numbers and ` → ` between the sides; a one-line range is not possible because of R5), `presence = MATCHED`, `moved = true`, and `oldRange`/`newRange` from the first line's start to the last line's end including its separator. `content` is `UNCHANGED` when every line's text is equal on both sides and `CHANGED` otherwise (R10), with `details` holding one `RangePair` per line that differs. Whitespace is significant here because the file's language is unknown, so the engine never reports `FORMATTING_ONLY`.
- `fileRegion`: null when every removed and added line is in a moved block. Otherwise `Subject.Region(FILE_REGION)` with `content = CHANGED` (R12), `oldRange`/`newRange` spanning the first to the last removed or added line not in a block on that side (an empty range at offset 0 when a side has none), and `details` listing each such line's range and each changed range inside a fragment that the line diff reports, so the nothing-hidden check (engine contract rule 6) holds.
- `status = ANALYZED`, `analysisNote = AnalysisNote.LINES` (`"lines"`, a new constant next to `STRUCTURE_VIEW`), so the summary line ends with `· lines` (R13). The existing summary counts the moved nodes as `moved`, plus `changed` for each block with whitespace edits, and the file region as `changed`.
- No `relationships`, so the refactorings view has nothing to list and reviewed marks are not specified for these rows.

When `changes` is empty, `OutlineController` shows a new state, `OutlineState.NoMovedLines`, with the text of R14, instead of the result. The file region alone would repeat the native diff.

### Outline behaviour

The rows are ordinary outline rows: `OutlineModel.title` already returns a region's description, so a block is titled `lines 12–18 → 40–46` (R9), and selection frames both ranges as it does for a moved declaration (R11). `MovedCodeMarkers.targets` reads `Tag.MOVED` from the shown rows, so moved blocks get markers without changes there (R15); a test checks that. The change type filter works on the labels, so hiding `moved` hides the blocks and hiding `changed` hides the file region (R16). The engine dropdown stays hidden because `EngineSelection` has no candidates (R17); `OutlinePanel.showEngines` already hides it below two candidates.

The outline never shows `No structural changes` or `unchanged` for a line comparison with differences (R20): a comparison with no block shows R14's message, and every other difference is in the file region row.

### Edge cases

- **Identical sides:** no fragments, so no blocks and no file region; the outline shows the R14 message.
- **Line separators only:** `ComparisonPolicy.DEFAULT` reports line-separator-only differences the way the native diff does; such lines become file region details.
- **A block moved and re-indented:** equal keys, different text: a block with `moved` and `changed`, with each re-indented line in `details` (R6, R10).
- **The same paragraph moved twice, or a removed block that also stays elsewhere as an added copy:** dropped by uniqueness (R7) and left in the file region.
- **A long file with many short repeated lines (CSV with equal rows):** uniqueness drops the repeated runs; the time limit bounds the rest (R18).
- **An engine is installed later for the language:** `engineFor` picks it on the next refresh, so the line comparison stops for that language (R4).

### Failure

An exception from the line diff, other than cancellation, becomes a `FAILED` result through `compareSafely`, and the outline shows `Analysis failed` with the reason, as for every engine. A file over the size limit is `TOO_LARGE` before the engine runs (R18).

## Tests

Each test names its requirement, for example `// engines/04-moved-lines R7`.

- `LineEngineTest` (platform test, since it uses `ComparisonManager`): a swapped pair of paragraphs gives one block with the exact title and ranges (R5, R9); a 2-line block and a 3-line block of `}` lines give none (R5); a block re-indented gives `moved` and `changed` with the re-indented lines in details (R6, R10); a block whose text appears twice among added lines is dropped (R7); an overlapping pair of candidates shares no line and the longer one wins (R8); blocks are sorted by the new side (R9); remaining edits are one file region with `changed` (R12); identical sides and a file with only edits give no block (R14); the result has `analysisNote = "lines"` (R13); every differing character is covered (engine contract rule 6, through the existing nothing-hidden checker); a canceled comparison throws (R18).
- Golden fixtures under `src/test/testData/lines/`: a plain-text changelog with a moved section and a typo, a CSV with moved rows, and a SQL script with a moved statement, each with its expected outline text (R2, R3, R9, R12, R13).
- `OutlineControllerLineTest` (platform test): a plain-text two-sided diff gets the outline and the line comparison with the setting on, and no panel with it off (R1, R3); a language with an engine uses that engine even when it reports `UNSUPPORTED` (R4); a one-sided diff of plain text gets no panel and a three-sided diff no merge outline (R19); the summary line ends with `· lines` (R13); no block shows R14's message (R14); selecting a block frames both sides (R11); moved-code markers appear on both editors for each block (R15); hiding `moved` in the filter hides the block rows (R16); the engine dropdown is hidden (R17); a file over the size limit shows `Too large to analyze` (R18); changing the setting and applying it refreshes an open outline (R21).
- `ChangesSummaryTest`: a plain-text file and a file in a language without an engine still get no Changes tree summary (R19).
- `SmartDiffConfigurableTest`: the checkbox is on by default and is stored in `SmartDiffSettings` (R1, R21); the default engine table has no `Lines` row (R17).

## Open questions

- Whether the thresholds in R5 suit long data files. The fixtures fix them for now; the validation corpus has no unsupported files yet, so a later corpus run could tune them.
