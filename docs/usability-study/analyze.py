#!/usr/bin/env python3
"""Compute the outline/06 R6 and R7 numbers from a filled results sheet.

Usage:
  python3 analyze.py results.csv
  python3 analyze.py --pilot pilot.csv
  python3 analyze.py --self-test

The results sheet has:
  participant,task,condition,time_seconds,correct
condition is native or outline; correct is yes or no.

The pilot sheet has:
  participant,task,run,time_seconds
run is 1 or 2, both native. The script reports the median relative
difference between the two runs; the protocol uses it to decide whether
the 20% threshold is above run-to-run variation.
"""

from __future__ import annotations

import argparse
import csv
import statistics
import sys
from pathlib import Path


def _read_rows(path: str) -> list[dict[str, str]]:
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def _condition(value: str) -> str:
    value = value.strip().lower()
    if value in {"native", "n"}:
        return "native"
    if value in {"outline", "o", "structural"}:
        return "outline"
    raise ValueError(f"unknown condition: {value!r}")


def _correct(value: str) -> bool:
    value = value.strip().lower()
    if value in {"yes", "true", "1", "y"}:
        return True
    if value in {"no", "false", "0", "n"}:
        return False
    raise ValueError(f"unknown correct value: {value!r}")


TASKS = ("T1", "T2", "T3", "T4", "T5")
CONDITIONS = ("native", "outline")


def _median(values: list[float]) -> float:
    if not values:
        raise ValueError("no values")
    return statistics.median(values)


def _complete(rows: list[dict[str, str]]) -> tuple[set[str], dict[tuple[str, str, str], float]]:
    """Return the participants and the rows keyed by participant/task/condition, or fail."""
    seen: dict[tuple[str, str, str], float] = {}
    participants: set[str] = set()
    for row in rows:
        participant = (row.get("participant") or "").strip()
        task = (row.get("task") or "").strip()
        if not participant or not task:
            raise ValueError("each row needs participant and task")
        if task not in TASKS:
            raise ValueError(f"unknown task: {task!r}")
        condition = _condition(row.get("condition", ""))
        try:
            time_seconds = float(row.get("time_seconds", ""))
        except ValueError as error:
            raise ValueError(f"bad time for {participant} {task} {condition}: {row.get('time_seconds')!r}") from error
        if time_seconds <= 0:
            raise ValueError(f"time must be positive for {participant} {task} {condition}")
        _correct(row.get("correct", ""))
        key = (participant, task, condition)
        if key in seen:
            raise ValueError(f"duplicate row for {participant} {task} {condition}")
        seen[key] = time_seconds
        participants.add(participant)
    for participant in participants:
        for task in TASKS:
            for condition in CONDITIONS:
                if (participant, task, condition) not in seen:
                    raise ValueError(f"missing row for {participant} {task} {condition}")
    return participants, seen


def analyze(rows: list[dict[str, str]]) -> int:
    if not rows:
        print("INCONCLUSIVE: the results sheet is empty.")
        return 2
    try:
        participants, seen = _complete(rows)
    except ValueError as error:
        print(f"INCONCLUSIVE: the results sheet is incomplete: {error}")
        return 2

    native_times: list[float] = []
    outline_times: list[float] = []
    native_wrong = 0
    outline_wrong = 0
    per_task: dict[str, dict[str, list[float]]] = {task: {"native": [], "outline": []} for task in TASKS}
    per_task_wrong: dict[str, dict[str, int]] = {task: {"native": 0, "outline": 0} for task in TASKS}

    for row in rows:
        participant = (row.get("participant") or "").strip()
        task = (row.get("task") or "").strip()
        condition = _condition(row.get("condition", ""))
        time_seconds = float(row.get("time_seconds", ""))
        correct = _correct(row.get("correct", ""))
        per_task[task][condition].append(time_seconds)
        per_task_wrong[task][condition] += 0 if correct else 1
        if condition == "native":
            native_times.append(time_seconds)
            native_wrong += 0 if correct else 1
        else:
            outline_times.append(time_seconds)
            outline_wrong += 0 if correct else 1

    native_median = _median(native_times)
    outline_median = _median(outline_times)
    reduction = (native_median - outline_median) / native_median * 100.0

    print(f"participants: {len(participants)}")
    print(f"native median: {native_median:.1f} s, wrong: {native_wrong}")
    print(f"outline median: {outline_median:.1f} s, wrong: {outline_wrong}")
    print(f"relative reduction: {reduction:.1f}% (needs at least 20.0%)")
    print()
    print("task | native median | outline median | reduction | native wrong | outline wrong")
    for task in sorted(per_task):
        n = per_task[task]["native"]
        o = per_task[task]["outline"]
        if not n or not o:
            print(f"{task:>4} | incomplete")
            continue
        nm = _median(n)
        om = _median(o)
        print(f"{task:>4} | {nm:13.1f} | {om:14.1f} | {(nm - om) / nm * 100:8.1f}% | "
              f"{per_task_wrong[task]['native']:12} | {per_task_wrong[task]['outline']:13}")

    print()
    if len(participants) < 5:
        print(f"INCONCLUSIVE: fewer than 5 participants ({len(participants)}). The 20% bar is not lowered.")
        return 2
    if reduction >= 20.0 and outline_wrong <= native_wrong:
        print("PASS: the median is at least 20% shorter and wrong answers did not rise.")
        return 0
    if reduction >= 20.0:
        print("FAIL: the median is faster, but wrong answers rose.")
        return 1
    print("FAIL: the median is less than 20% shorter.")
    return 1


def analyze_pilot(rows: list[dict[str, str]]) -> int:
    if not rows:
        print("INCONCLUSIVE: the pilot sheet is empty.")
        return 2
    by_pair: dict[tuple[str, str, str], float] = {}
    participants: set[str] = set()
    for row in rows:
        participant = (row.get("participant") or "").strip()
        task = (row.get("task") or "").strip()
        run = (row.get("run") or "").strip()
        if not participant or not task or run not in {"1", "2"}:
            print("INCONCLUSIVE: each pilot row needs participant, task and run 1 or 2")
            return 2
        if task not in TASKS:
            print(f"INCONCLUSIVE: unknown task {task!r}")
            return 2
        try:
            time_seconds = float(row.get("time_seconds", ""))
        except ValueError:
            print(f"INCONCLUSIVE: bad time for {participant} {task} run {run}")
            return 2
        if time_seconds <= 0:
            print(f"INCONCLUSIVE: time must be positive for {participant} {task} run {run}")
            return 2
        key = (participant, task, run)
        if key in by_pair:
            print(f"INCONCLUSIVE: duplicate pilot row for {participant} {task} run {run}")
            return 2
        by_pair[key] = time_seconds
        participants.add(participant)
    if len(participants) != 2:
        print(f"INCONCLUSIVE: the pilot needs exactly 2 participants ({len(participants)}).")
        return 2
    for participant in participants:
        for task in TASKS:
            for run in ("1", "2"):
                if (participant, task, run) not in by_pair:
                    print(f"INCONCLUSIVE: missing pilot row for {participant} {task} run {run}")
                    return 2
    run1 = [by_pair[(p, t, "1")] for p in participants for t in TASKS]
    run2 = [by_pair[(p, t, "2")] for p in participants for t in TASKS]
    first = _median(run1)
    second = _median(run2)
    median_difference = abs(first - second) / min(first, second) * 100.0
    print(f"pilot participants: {len(participants)}")
    print(f"native run 1 median: {first:.1f} s; run 2 median: {second:.1f} s")
    print(f"median run-to-run difference: {median_difference:.1f} percentage points")
    if median_difference < 10.0:
        print("PILOT OK: the 20% threshold is above the observed variation.")
        return 0
    if median_difference < 20.0:
        print("PILOT CAUTION: 20% is above variation but close; report the pilot with the result.")
        return 0
    print("PILOT INCONCLUSIVE: variation is at least 20 points; revise the tasks or timing before the main study.")
    return 2


def _self_test() -> int:
    def rows_for(participants: int, outline_time: float, outline_wrong_first: bool = False) -> list[dict[str, str]]:
        rows = []
        for p in range(participants):
            for t in range(1, 6):
                rows.append({"participant": f"P{p + 1:02}", "task": f"T{t}", "order": str(t),
                             "condition": "native", "time_seconds": "100", "correct": "yes"})
                wrong = "no" if outline_wrong_first and p == 0 and t == 1 else "yes"
                rows.append({"participant": f"P{p + 1:02}", "task": f"T{t}", "order": str(t),
                             "condition": "outline", "time_seconds": str(outline_time), "correct": wrong})
        return rows

    if analyze(rows_for(5, 75)) != 0:
        print("self-test failed: the 25% case should pass", file=sys.stderr)
        return 1
    if analyze(rows_for(4, 75)) != 2:
        print("self-test failed: four participants should be inconclusive", file=sys.stderr)
        return 1
    incomplete = [r for r in rows_for(5, 75) if not (r["participant"] == "P05" and r["task"] == "T5" and r["condition"] == "outline")]
    if analyze(incomplete) != 2:
        print("self-test failed: an incomplete matrix should be inconclusive", file=sys.stderr)
        return 1
    if analyze(rows_for(5, 75, outline_wrong_first=True)) != 1:
        print("self-test failed: a faster but wrong outline should fail", file=sys.stderr)
        return 1
    pilot = []
    for participant, first, second in (("P01", "100", "105"), ("P02", "80", "84")):
        for task in TASKS:
            pilot.append({"participant": participant, "task": task, "run": "1", "time_seconds": first})
            pilot.append({"participant": participant, "task": task, "run": "2", "time_seconds": second})
    if analyze_pilot(pilot) != 0:
        print("self-test failed: a 5% pilot should be ok", file=sys.stderr)
        return 1
    print("self-test passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sheet", nargs="?", help="results or pilot CSV")
    parser.add_argument("--pilot", action="store_true", help="the sheet is a pilot sheet")
    parser.add_argument("--self-test", action="store_true", help="check the arithmetic with synthetic data")
    args = parser.parse_args()

    if args.self_test:
        return _self_test()
    if not args.sheet:
        parser.error("a sheet path is required unless --self-test is used")
    rows = _read_rows(args.sheet)
    return analyze_pilot(rows) if args.pilot else analyze(rows)


if __name__ == "__main__":
    sys.exit(main())
