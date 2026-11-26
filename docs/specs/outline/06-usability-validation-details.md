# Usability validation: details

Status: accepted, 2026-11-26

Feature: [Usability validation](06-usability-validation.md)

## Design

### Tasks

Each task is one commit or saved diff that touches at least two [declarations](../../GLOSSARY.md#declaration) in one file, with at least two hunks outside each other's context. The expected answer names the declaration and the kind of change, taken from `git show` and checked against the outline. Tasks cover Java, Kotlin and Scala, plus one [kind change](../../GLOSSARY.md#kind-change) and one [rename candidate](../../GLOSSARY.md#rename-candidate), so the study does not measure only the easy cases.

The task sheet shows the diff without the outline for the native condition, and the same diff with the outline for the structural condition. Both conditions use the same IDE version, font size and window size.

### Protocol

1. Brief each participant on the two conditions and on the answer format, without naming the task's declaration.
2. Give each participant the five tasks once each (R11). The order schedule fixes the condition of every task: odd-numbered participants do T1, T3 and T5 with the native diff and T2 and T4 with the outline, and even-numbered participants do the opposite (R5, R12).
3. Record the start and end time on the task sheet, and mark the answer right or wrong.
4. Analyze the median time per condition and the number of wrong answers over all runs. Report the per-task results as well, since 6 participants cannot support a claim about a small difference.

### Why each task is done once

A participant who has answered a task once knows the answer the second time, so a second run of the same task measures recall, not search. The first design (R4, retired 2026-11-26) gave every participant every task in both conditions, which made the second condition of every task faster for that reason alone. With one run per task, the comparison is between participants for each task and within participants across tasks: every participant uses both conditions, and every task is done equally often with each.

R12 needs an even number of participants, so the main study needs at least 6 (P01 to P06, R13) instead of the 5 of the retired R3. With 6, every task has 3 native and 3 outline runs, and each condition has 15 runs. A seventh participant is not counted until an eighth completes the study; the analysis reports an odd count as incomplete.

The study is done by the maintainer, not by an external lab. Participants may be contributors or colleagues who did not make the change under test. The study records no names.

### Why these thresholds

The 20% threshold is a choice: it is large enough to matter for a reviewer's time and small enough to be plausible for a study this size. The pilot in the Decisions section measures the run-to-run variation before the main study; if the variation is 20% or more, the study is reported as inconclusive rather than lowering the threshold. The accuracy guard keeps a faster but wrong answer from passing. The sample is small on purpose: it is a release check, not a research result.

### Failure behavior

If the outline does not pass, the results still go into VALIDATION.md, and the gap becomes a roadmap item. A failed study is not hidden and does not block a release by itself; the release notes must not claim a speed benefit.

## Study kit

The kit is in `docs/usability-study/`:

- `protocol.md` — setup, the two conditions, the order schedule, timing rules and the right-answer rule.
- `tasks.md` — the five pinned tasks, with the repository, commit, parent, file, question and expected answer.
- `facilitator-script.md` — the words the facilitator reads.
- `results-sheet-template.csv` and `attendance-sheet-template.csv` — the anonymous result and qualification sheets.
- `pilot-sheet-template.csv` — the two native runs for the pilot.
- `analyze.py` — turns a filled sheet into the R6/R7 numbers and the pass/inconclusive result.

The study compares the [container tree](../../GLOSSARY.md#container-tree) with the native diff. The refactorings view is not a condition: it changes the question and is an optional view, so adding it would make the sample too small to interpret.

## Decisions

- **Pilot size and variation.** The pilot has 2 participants, X01 and X02, who are not counted in the main study. Each does the five tasks once, all with the native diff, but each run is labelled with the condition the order schedule would give it: X01 follows P01's row and X02 follows P02's row. The analysis then computes the same relative difference as the main study, the absolute difference of the two medians divided by the median of the runs labelled native, between the runs labelled native and the runs labelled outline. Since no run used the outline, that difference is the variation from participants and tasks alone. If it is below 10%, 20% is above the observed variation. If it is 10–20%, the main study may run but its result is reported with that caution. If it is 20% or more, the 20% threshold is not above variation, so the study is reported as inconclusive and the tasks or timing are revised before the main study.
- **Fewer than 6 participants.** The study is reported as inconclusive, and so is an odd number of participants (R12). The bar is not lowered and the feature is not marked implemented; the maintainer waits for more participants or records the study as not run.
- **Comparison condition.** Only the container tree against the native diff, as the feature says. The refactorings view is out of scope for this study.

## Tests

| Requirement | Check |
| --- | --- |
| R1, R2 | `tasks.md` pins each task to a real commit, file and parent, and each expected answer is checked against `git show` and the outline before the study |
| R3 | Retired; replaced by R13 |
| R4 | Retired; replaced by R11 and R12 |
| R5 | The order schedule in `protocol.md`; `analyze.py` rejects a run whose condition differs from it |
| R6 | The per-task result table from `results-sheet-template.csv` |
| R7 | `analyze.py` computes the median times, the relative difference and the wrong-answer counts; the pilot rule above decides whether 20% is above variation |
| R8, R9 | The dated VALIDATION.md section and the stored task sheet; `analyze.py` can be run again on a later sheet |
| R10 | Review of the published record for personal data before it is committed |
| R11 | `analyze.py` requires every participant to have every task exactly once and rejects missing or duplicate rows |
| R12 | `analyze.py` checks each run's condition against the order schedule and reports an odd number of participants, or codes that are not P01 to PN, as inconclusive |
| R13 | The attendance sheet records `qualified=yes` for every participant; fewer than 6 participants is reported as inconclusive |
