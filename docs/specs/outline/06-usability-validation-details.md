# Usability validation: details

Status: accepted, 2026-11-10

Feature: [Usability validation](06-usability-validation.md)

## Design

### Tasks

Each task is one commit or saved diff that touches at least two [declarations](../../GLOSSARY.md#declaration) in one file, with at least two hunks outside each other's context. The expected answer names the declaration and the kind of change, taken from `git show` and checked against the outline. Tasks cover Java, Kotlin and Scala, plus one [kind change](../../GLOSSARY.md#kind-change) and one [rename candidate](../../GLOSSARY.md#rename-candidate), so the study does not measure only the easy cases.

The task sheet shows the diff without the outline for the native condition, and the same diff with the outline for the structural condition. Both conditions use the same IDE version, font size and window size.

### Protocol

1. Brief each participant on the two conditions and on the answer format, without naming the task's declaration.
2. Run the two conditions in alternating order, with a short break between them.
3. Record the start and end time on the task sheet, and mark the answer right or wrong.
4. Analyze the median time per condition and the number of wrong answers. Report the per-task results as well, since 5 participants cannot support a claim about a small difference.

The study is done by the maintainer, not by an external lab. Participants may be contributors or colleagues who did not make the change under test. The study records no names.

### Why these thresholds

The 20% threshold is a choice: it is large enough to matter for a reviewer's time and small enough to be plausible for a study this size. The pilot in the Decisions section measures the run-to-run variation before the main study; if the variation is 20 points or more, the study is reported as inconclusive rather than lowering the threshold. The accuracy guard keeps a faster but wrong answer from passing. The sample is small on purpose: it is a release check, not a research result.

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

- **Pilot size and variation.** The pilot has 2 participants, each doing the five tasks twice under the native condition. The analysis compares the two native medians. If the median relative difference is below 10 percentage points, 20% is above the observed variation. If it is 10–20 points, the main study may run but its result is reported with that caution. If it is 20 points or more, the 20% threshold is not above variation, so the study is reported as inconclusive and the tasks or timing are revised before the main study.
- **Fewer than 5 participants.** The study is reported as inconclusive. The bar is not lowered and the feature is not marked implemented; the maintainer waits for more participants or records the study as not run.
- **Comparison condition.** Only the container tree against the native diff, as the feature says. The refactorings view is out of scope for this study.

## Tests

| Requirement | Check |
| --- | --- |
| R1, R2 | `tasks.md` pins each task to a real commit, file and parent, and each expected answer is checked against `git show` and the outline before the study |
| R3 | The attendance sheet records `qualified=yes` for every participant; fewer than 5 participants is reported as inconclusive |
| R4 | `analyze.py` requires every participant to have every task in both conditions and rejects missing or duplicate rows |
| R5 | The order schedule in `protocol.md`; the facilitator follows it for every participant |
| R6 | The per-task result table from `results-sheet-template.csv` |
| R7 | `analyze.py` computes the median times, the relative difference and the wrong-answer counts; the pilot rule above decides whether 20% is above variation |
| R8, R9 | The dated VALIDATION.md section and the stored task sheet; `analyze.py` can be run again on a later sheet |
| R10 | Review of the published record for personal data before it is committed |
