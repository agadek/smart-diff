#!/usr/bin/env python3
"""Compute the outline/06 R6 and R7 numbers from a filled results sheet.

Usage:
  python3 analyze.py results.csv
  python3 analyze.py --pilot pilot.csv
  python3 analyze.py --self-test

The results sheet has:
  participant,task,order,condition,time_seconds,correct
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


def _median(values: list[float]) -> float:
    if not values:
        raise ValueError("no values")
    return statistics.median(values)


def analyze(rows: list[dict[str, str]]) -> int:
    if not rows:
        print("INCONCLUSIVE: the results sheet is empty.")
        return 2

    native_times: list[float] = []
    outline_times: list[float] = []
    native_wrong = 0
    outline_wrong = 0
    participants: set[str] = set()
    per_task: dict[str, dict[str, list[float]]] = {}
    per_task_wrong: dict[str, dict[str, int]] = {}

    for row in rows:
        participant = (row.get("participant") or "").strip()
        task = (row.get("task") or "").strip()
        if not participant or not task:
            raise ValueError("each row needs participant and task")
        condition = _condition(row.get("condition", ""))
        time_seconds = float(row.get("time_seconds", "0"))
        correct = _correct(row.get("correct", ""))
        participants.add(participant)
        per_task.setdefault(task, {"native": [], "outline": []})[condition].append(time_seconds)
        per_task_wrong.setdefault(task, {"native": 0, "outline": 0})[condition] += 0 if correct else 1
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
    by_task: dict[tuple[str, str], dict[str, float]] = {}
    for row in rows:
        participant = (row.get("participant") or "").strip()
        task = (row.get("task") or "").strip()
        run = (row.get("run") or "").strip()
        if not participant or not task or run not in {"1", "2"}:
            raise ValueError("each pilot row needs participant, task and run 1 or 2")
        by_task.setdefault((participant, task), {})[run] = float(row.get("time_seconds", "0"))
    differences: list[float] = []
    for (participant, task), runs in sorted(by_task.items()):
        if "1" not in runs or "2" not in runs:
            print(f"incomplete pilot pair: {participant} {task}")
            continue
        first, second = runs["1"], runs["2"]
        baseline = min(first, second)
        difference = abs(first - second) / baseline * 100.0 if baseline else 0.0
        differences.append(difference)
        print(f"{participant} {task}: run 1 {first:.1f} s, run 2 {second:.1f} s, difference {difference:.1f}%")
    if not differences:
        print("INCONCLUSIVE: no complete pilot pairs.")
        return 2
    median_difference = _median(differences)
    print()
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
    if analyze(rows_for(5, 75, outline_wrong_first=True)) != 1:
        print("self-test failed: a faster but wrong outline should fail", file=sys.stderr)
        return 1
    pilot = [
        {"participant": "P01", "task": "T1", "run": "1", "time_seconds": "100"},
        {"participant": "P01", "task": "T1", "run": "2", "time_seconds": "105"},
        {"participant": "P02", "task": "T1", "run": "1", "time_seconds": "80"},
        {"participant": "P02", "task": "T1", "run": "2", "time_seconds": "84"},
    ]
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
