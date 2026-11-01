# Usability validation: details

Status: proposed, 2026-11-01

Feature: [Usability validation](01-usability-validation.md)

## Design

### Tasks

Each task is one commit or saved diff that touches at least 3 declarations in one file, with at least two hunks outside each other's context. The expected answer names the declaration and the kind of change, taken from `git show` and checked against the outline. Tasks cover one Java file, one Kotlin file and one Scala file, plus one kind change and one rename candidate, so the study does not measure only the easy cases.

The task sheet shows the diff without the outline for the native condition, and the same diff with the outline for the structural condition. Both conditions use the same IDE version, font size and window size.

### Protocol

1. Brief each participant on the two conditions and on the answer format, without naming the task's declaration.
2. Run the two conditions in alternating order, with a short break between them.
3. Record the start and end time on the task sheet, and mark the answer right or wrong.
4. Analyze the median time per condition and the number of wrong answers. Report the per-task results as well, since 5 participants cannot support a claim about a small difference.

The study is done by the maintainer, not by an external lab. Participants may be contributors or colleagues who did not make the change under test. The study records no names.

### Why these thresholds

The 20% threshold is larger than the variation seen between two runs of the same condition in a pilot, and small enough to matter for a reviewer's time. The accuracy guard keeps a faster but wrong answer from passing. The sample is small on purpose: it is a release check, not a research result, and a larger study would need more participants than the project has.

### Failure behavior

If the outline does not pass, the results still go into VALIDATION.md, and the gap becomes a roadmap item. A failed study is not hidden and does not block a release by itself; the release notes must not claim a speed benefit.

## Tests

| Requirement | Check |
| --- | --- |
| R1, R2 | Manual review of the task sheet against `git show` before the study |
| R3, R4 | Attendance sheet with anonymous participant codes |
| R5 | The task order table in the study record |
| R6 | The per-task result table |
| R7 | The analysis of the result table |
| R8, R9 | The VALIDATION.md section and its stored task sheet |
| R10 | Review of the published record for personal data |
