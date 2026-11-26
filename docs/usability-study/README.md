# Usability study kit

Status: prepared, 2026-11-10; revised 2026-11-26 to one run per task; no sessions have been run.

This kit prepares the small usability study in [outline/06](../specs/outline/06-usability-validation.md). The maintainer runs the sessions; this repository only holds the protocol and the analysis tools. It does not recruit participants and stores no personal data.

## What the maintainer does

1. Read [protocol.md](protocol.md) and [facilitator-script.md](facilitator-script.md).
2. Take the five task repositories from [tasks.md](tasks.md) with `prepare-task.sh <corpus-root> <T1..T5> <dir>`. The corpus root is the directory that holds the pinned clones (`okhttp`, `spring-petclinic`, `zio`, `scala3`, `Exposed`).
3. Run the pilot with 2 participants, `X01` and `X02`, each doing the five tasks once under the native condition. Check it with `python3 analyze.py --pilot pilot.csv`.
4. If the pilot is acceptable, run the sessions with at least 6 participants, an even number, in the order schedule; each participant does each task once; screen each one and record only `qualified=yes` in [attendance-sheet-template.csv](attendance-sheet-template.csv).
5. Fill [results-sheet-template.csv](results-sheet-template.csv) with one anonymous row per participant, task and condition; save it as `results.csv`. Fill [pilot-sheet-template.csv](pilot-sheet-template.csv) for the pilot, one row per participant and task.
6. Run `python3 analyze.py results.csv`. The script prints the medians, the relative difference, the wrong counts and the pass/inconclusive result.
7. Record the method, tasks, pilot and result in a dated section of [VALIDATION.md](../VALIDATION.md), separate from the corpus results. Mark outline/06 implemented only if at least 6 participants completed the study and R7 passed.

## Rules

- Never record names or other personal data; use participant codes (R10).
- The study compares the container tree with the native diff. The refactorings view is not a condition.
- Fewer than 6 participants, or an odd number, is **inconclusive**; do not lower the 20% bar.
- Each participant does each task once. Never time a second run of a task the participant has already answered.
- The release notes may claim that the outline makes reviews faster only after the study passes outline/06 R7.

## Files

- [protocol.md](protocol.md) — setup, conditions, order schedule, timing and the pilot rule.
- [tasks.md](tasks.md) — the five pinned tasks, questions and expected answers; facilitator-only.
- [facilitator-script.md](facilitator-script.md) — the words to read.
- [prepare-task.sh](prepare-task.sh) — builds one task repository from the pinned corpus commit.
- [results-sheet-template.csv](results-sheet-template.csv) — the result columns.
- [attendance-sheet-template.csv](attendance-sheet-template.csv) — the anonymous qualification check.
- [pilot-sheet-template.csv](pilot-sheet-template.csv) — the native pilot runs.
- [analyze.py](analyze.py) — the R6/R7 analysis and the pilot check; `--self-test` checks the arithmetic.
- [dry-run.md](dry-run.md) — the non-participant dry run record.
