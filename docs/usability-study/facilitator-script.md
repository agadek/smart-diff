# Facilitator script

Status: prepared, 2026-11-10

Read this script before each session. Do not add explanations that identify the task's declaration or answer.

## Before the participant arrives

- Prepare the task repository and open it in the IDE.
- Close unrelated files and tool windows.
- Put the participant code on the results sheet: `P01`, `P02`, and so on.
- Do not write any name or other personal data anywhere.

## Screening

Before the session, ask whether the participant knows Java, Kotlin and Scala and did not author the change under test. Record only `qualified=yes` or `qualified=no` in `attendance-sheet-template.csv`. Do not record names, roles or employers.

## Introduction

> Thanks for helping. This is a short study of how quickly a reviewer finds a changed declaration in a diff. You will see the same change twice: once with the IDE's native diff alone, and once with a structural outline beside it. Each time I will read one question. Please tell me the declaration and how it changed. I will time you. There are five tasks, and each takes two runs. You can scroll and expand rows, but please do not use Search Everywhere, the Project view, or the source editor. I only record a participant code, not your name. You can stop at any time.

Ask whether the participant has questions. Do not answer questions about the specific change.

## For each task

1. Read the task's question from `tasks.md` exactly once.
2. Show the diff for the condition in the order schedule. Start the timer when the diff is visible.
3. Stop the timer when the participant states the declaration and the change.
4. Record the time and `yes` or `no` on the results sheet. Do not correct the answer.
5. If the participant has not answered after 180 seconds, stop, record 180 and `no`.
6. Switch to the other condition after a short break. Do not reveal the answer between the two conditions.

## Pilot only

For the pilot, the participant does the five tasks twice under the native condition only; there is no outline run. Use `pilot-sheet-template.csv` with `participant,task,run,time_seconds`. Do not count pilot runs in the main results.

## Between tasks

> We'll take a short break, then do the next task.

Do not discuss the previous answer until the whole session is over. After the session, you may explain the answers if the participant asks.

## After the session

- Check that the sheet has one row per task and condition, with no names.
- Thank the participant.
- Do not record the answers in the repository until the study is complete; then use `analyze.py` and write the dated VALIDATION.md section.
