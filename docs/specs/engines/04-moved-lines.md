# Moved lines in unsupported files

Status: implemented, 2026-11-16

**Description:** A two-sided diff of a file that no [comparison engine](../../GLOSSARY.md#comparison-engine) supports, plain text included, is compared by the [line comparison](../../GLOSSARY.md#line-comparison). The [structural outline](../../GLOSSARY.md#structural-outline) then lists each [moved block](../../GLOSSARY.md#moved-block) of lines, framed on both sides, and one row for every other change.

**Use case:** A reviewer reads a diff of a CSV export, a SQL script in an IDE without SQL support, or a plain-text changelog where a teammate reordered sections and fixed a typo. The native diff shows every reordered section as removed and added lines. The outline lists `lines 12–18 → 40–46` as moved, so the reviewer reads the moved text once and spends their attention on the typo.

## Requirements

- **R1** "Show moved lines in files without an engine" is on by default, and with it off, diffs behave as without this feature.
- **R2** A two-sided text diff whose two sides are in the same language, which no comparison engine supports, is compared by the line comparison.
- **R3** Plain-text files are compared by the line comparison too.
- **R4** A language that any comparison engine supports is never compared by the line comparison, even when that engine reports a fallback state.
- **R5** A moved block is a run of at least 3 consecutive lines, with at least 30 characters that are not whitespace, that the native diff shows as removed on the [old side](../../GLOSSARY.md#old-side) and as added in one run on the [new side](../../GLOSSARY.md#new-side).
- **R6** Two lines of a moved block are the same when their text is equal once whitespace at the start and end of each line is left out.
- **R7** A block whose text the old side's removed lines or the new side's added lines hold more than once is not a moved block.
- **R8** Each line belongs to at most one moved block, and longer blocks are found first.
- **R9** The outline shows each moved block as a row titled `lines A–B → C–D`, with the old side's line numbers first, counted from 1, in the order of the new side.
- **R10** A moved block row has the `moved` label, and also `changed` when any of its lines differs in whitespace.
- **R11** Selecting a moved block row frames its lines on both sides.
- **R12** Every change outside the moved blocks is shown as one [file region](../../GLOSSARY.md#file-region) row with the `changed` label.
- **R13** The [summary line](../../GLOSSARY.md#summary-line) counts moved blocks as moved and the file region as changed, and ends with `· lines`.
- **R14** A comparison without a moved block shows the message `No moved lines found. The native diff shows every change.` and no rows.
- **R15** Moved blocks get [moved-code markers](../../GLOSSARY.md#moved-code-marker) in both editors, as moved declarations do.
- **R16** The [change type filter](../../GLOSSARY.md#change-type-filter) hides moved block rows and the file region row by their labels.
- **R17** The [engine dropdown](../../GLOSSARY.md#engine-dropdown) is not shown for a file compared by lines, and the settings page lists no default engine for it.
- **R18** A file over the [analysis limits](../../GLOSSARY.md#analysis-limits) shows `Too large to analyze`, and a comparison over the time limit shows `Analysis failed` with `timed out`.
- **R19** One-sided diffs, three-sided diffs, the merge window and the [Changes tree](../../GLOSSARY.md#changes-tree) do not use the line comparison.
- **R20** The line comparison never says that two versions behave the same, and the native diff keeps showing every change.
- **R21** The setting is a checkbox on the settings page, and changing it there applies to open diffs when applied.

## Out of scope

- Edits inside a moved block other than whitespace, which [Moves inside declarations](../../ROADMAP.md#moves-inside-declarations) covers for supported languages.
- Lines moved between files.
- Moved lines inside a declaration of a supported language.
- Reviewed marks and the refactorings view for moved blocks.
- Binary files and diffs that are not text.

## References

- [Details: design and tests](04-moved-lines-details.md)
- [Comparison engines](01-comparison-engines.md)
- [Analysis fallback states](../outline/05-analysis-fallback.md) R1, which applies while the setting is off
- [Moved-code markers](../outline/13-moved-code-markers.md)
- [Settings page](../settings/01-settings-page.md) R21
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Roadmap: Moved lines in unsupported files](../../ROADMAP.md#moved-lines-in-unsupported-files)
