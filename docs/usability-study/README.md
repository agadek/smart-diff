# Usability study kit

Status: prepared, 2026-11-10; no sessions have been run.

This kit prepares the small usability study in [outline/06](../specs/outline/06-usability-validation.md). The maintainer runs the sessions; this repository only holds the protocol and the analysis tools. It does not recruit participants and stores no personal data.

## What the maintainer does

1. Read [protocol.md](protocol.md) and [facilitator-script.md](facilitator-script.md).
2. Take the five task repositories from [tasks.md](tasks.md) with `prepare-task.sh <corpus-root> <T1..T5> <dir>`. The corpus root is the directory that holds the pinned clones (`okhttp`, `spring-petclinic`, `zio`, `scala3`, `Exposed`).
3. Run the pilot with 2 participants, each doing the five tasks twice under the native condition. Check it with `python3 analyze.py --pilot pilot.csv`.
4. If the pilot is acceptable, run the sessions with at least 5 participants in the order schedule.
5. Fill [results-sheet-template.csv](results-sheet-template.csv) with one anonymous row per participant, task and condition; save it as `results.csv`.
6. Run `python3 analyze.py results.csv`. The script prints the medians, the relative difference, the wrong counts and the pass/inconclusive result.
7. Record the method, tasks, pilot and result in a dated section of [VALIDATION.md](../VALIDATION.md), separate from the corpus results. Mark outline/06 implemented only if at least 5 participants completed the study and R7 passed.

## Rules

- Never record names or other personal data; use participant codes (R10).
- The study compares the container tree with the native diff. The refactorings view is not a condition.
- Fewer than 5 participants is **inconclusive**; do not lower the 20% bar.
- The release notes may claim that the outline makes reviews faster only after the study passes outline/06 R7.

## Files

- [protocol.md](protocol.md) — setup, conditions, order schedule, timing and the pilot rule.
- [tasks.md](tasks.md) — the five pinned tasks, questions and expected answers; facilitator-only.
- [facilitator-script.md](facilitator-script.md) — the words to read.
- [prepare-task.sh](prepare-task.sh) — builds one task repository from the pinned corpus commit.
- [results-sheet-template.csv](results-sheet-template.csv) — the result columns.
- [analyze.py](analyze.py) — the R6/R7 analysis and the pilot check; `--self-test` checks the arithmetic.
- [dry-run.md](dry-run.md) — the non-participant dry run record.
