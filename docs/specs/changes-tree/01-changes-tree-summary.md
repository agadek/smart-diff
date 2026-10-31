# Changes tree summary

Status: implemented, 2026-10-22

**Description:** Each changed file in the [Changes tree](../../GLOSSARY.md#changes-tree) shows its [summary line](../../GLOSSARY.md#summary-line) after its name.

**Use case:** Before committing, a developer looks over Local Changes. Next to each file they see what changed, such as `Parser.kt  2 changed · 1 added`, without opening any diff.

## Requirements

- **R1** The summary appears in the Local Changes view and the Commit tool window.
- **R2** It is shown in grey after the file name, starting in lower case.
- **R3** Only modified and moved files whose language has a [comparison engine](../../GLOSSARY.md#comparison-engine) get a summary.
- **R4** Added and deleted files get no summary.
- **R5** A file that could not be analyzed shows the reason in grey instead: `not analyzed: too large`, `not analyzed: timed out`, `not analyzed: parse errors` or `not analyzed: analysis failed`.
- **R6** A file that timed out is tried once more after a short delay, and keeps its reason meanwhile.
- **R7** At most 500 files of a tree get a summary.
- **R8** Summaries are computed in the background, one file at a time, with the usual [analysis limits](../../GLOSSARY.md#analysis-limits).
- **R9** An edit to a file, or a change of engine, gives it a new summary.
- **R10** The tree is rebuilt at most once every 300 ms while summaries arrive.
- **R11** The summary counts every change, and the [change type filter](../../GLOSSARY.md#change-type-filter) does not apply to it.
- **R12** The setting "Show structural summary in the Changes tree" turns it off.
- **R13** Changing any SMART Diff setting computes the summaries again.

## Out of scope

- The modal commit dialog and the Git log's commit details, which have no hook for it.

## References

- [ADR 0006](../../adr/0006-changes-tree-summary-through-changes-view-modifier.md)
- [Technical specification §9.4](../../TECHNICAL-SPECIFICATION.md#94-changes-tree-summary)
