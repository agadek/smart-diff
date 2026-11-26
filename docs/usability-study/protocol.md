# Usability study protocol

Status: prepared, 2026-11-10; revised 2026-11-26 (one run per task)

This protocol checks [outline/06](../specs/outline/06-usability-validation.md) R1 to R13: whether a reviewer finds one changed declaration faster with the structural outline than with the native diff alone, without more wrong answers. The maintainer runs the sessions; this document and the kit do not recruit or contact anyone.

## What is measured

- **N (native):** the IDE's native side-by-side diff with the structural outline hidden.
- **O (outline):** the same native diff with the structural outline shown beside it.
- The refactorings view is **not** a condition. The study compares the container tree with the native diff, as outline/06 says.

Each participant does every task once, in the condition the order schedule gives it (R11). Every participant uses both conditions, and every task is done by as many participants in each condition (R12). A second run of the same task is never timed: the participant would already know the answer.

## Setup

- Use IntelliJ IDEA 2025.1.3 with the SMART Diff build under test. The same IDE version, font size, window size and theme are used for every session.
- Prepare one task repository with `prepare-task.sh <corpus-root> <T1..T5> <dir>` and open that directory in the IDE. Local Changes shows the one changed file.
- Close unrelated editors and tool windows. Do not show the facilitator's expected answers to the participant.
- For N, hide the outline with the outline toolbar toggle or the panel's Hide button. For O, show it. The native diff stays the same; the setting only controls whether a new diff opens with the outline.
- In *Settings | Tools | SMART Diff*, tick nothing under **Hide from the outline and the Changes tree** (the filter is shared since 0.5.0), and leave moved-code detection at its default (on).
- Screen each participant before the session: they must read Java, Kotlin and Scala and must not have authored the change under test. Record only `qualified=yes` or `qualified=no` on `attendance-sheet-template.csv`; do not record names, roles or employers.
- The participant may scroll and expand rows in either condition. They may not use Search Everywhere, the Project view, or the file's source editor; the task is to read the diff.
- A timer starts when the diff is visible and the question has been read, and stops when the participant states the declaration and how it changed. Record seconds to one decimal.

## Order schedule

The facilitator reads the tasks in the order T1 to T5. `N` means the task is done with the native diff alone, `O` with the outline. Participant codes are anonymous and assigned in order.

| Participant | T1 | T2 | T3 | T4 | T5 |
| --- | --- | --- | --- | --- | --- |
| P01 | N | O | N | O | N |
| P02 | O | N | O | N | O |
| P03 | N | O | N | O | N |
| P04 | O | N | O | N | O |
| P05 | N | O | N | O | N |
| P06 | O | N | O | N | O |

Further participants continue the pattern: odd codes follow P01's row, even codes P02's. The schedule alternates both between the tasks of one participant and between participants. With 6 participants every task has 3 native and 3 outline runs; a seventh participant is counted only once an eighth completes the study.

## Timing and answers

- One timed run per task. Do not repeat a run because the participant is unhappy with it.
- A run ends at 180 seconds if the participant has not answered. Record 180 and mark it wrong.
- A right answer names the expected declaration and the kind of change. Exact wording is not required; a wrong declaration, a wrong direction (for example "removed" for an added method), or naming only the file is wrong.
- The facilitator does not point at a row, name the declaration, or confirm an answer before the timer stops.
- Take a short break between tasks and a longer break between participants if needed.

## Pilot

Run the pilot before the main sessions, with 2 participants, `X01` and `X02`, who are not counted in the main study.

- Each pilot participant does the five tasks once, all with the native diff, with the same setup and timing. Use `pilot-sheet-template.csv` with `participant,task,time_seconds`.
- `analyze.py --pilot` labels each run with the condition the schedule would give it (X01 follows P01's row, X02 follows P02's) and computes the same relative difference as the main study between the two labels. No run used the outline, so the difference is the variation from participants and tasks alone.
- If the difference is below 10 percentage points, the 20% threshold is above the observed variation; run the main study.
- If it is 10–20 points, run the main study but report this caution with the result.
- If it is 20 points or more, the 20% threshold is not above variation. Do not run the main study; report the pilot as inconclusive and revise the tasks or timing.

## Fewer than 6 participants

Do not lower the 20% bar. If fewer than 6 participants, or an odd number, complete the study, report the result as **inconclusive** and do not mark outline/06 implemented. The maintainer may wait for more participants or record the study as not run.

## Results and privacy

- Record only the participant code, task ID, condition, time and right/wrong. Never record names, roles, employers or other personal data (R10). The condition must match the schedule above; `analyze.py` rejects a sheet where it does not.
- Put the filled sheet in `results.csv`; run `python3 analyze.py results.csv`. The script requires every participant to have every task exactly once, in the scheduled condition, and reports an incomplete sheet or an odd number of participants as inconclusive.
- Record the method, tasks, pilot and results in a dated section of [VALIDATION.md](../VALIDATION.md), separate from the corpus classification results (R8).
- The task pins and the analysis script are the repeatable kit (R9). A later study uses the same task table and a new results sheet.

## Files

- `tasks.md` — the five pinned tasks, questions and expected answers; facilitator-only.
- `prepare-task.sh` — builds one task repository from the pinned corpus commit.
- `facilitator-script.md` — the words the facilitator reads.
- `results-sheet-template.csv` — the columns for the filled sheet (`participant,task,condition,time_seconds,correct`).
- `attendance-sheet-template.csv` — the anonymous qualification check (`participant,qualified`).
- `pilot-sheet-template.csv` — the pilot runs (`participant,task,time_seconds`).
- `analyze.py` — computes the R6/R7 numbers and the pass/inconclusive result; `--self-test` checks the arithmetic.
