#!/usr/bin/env python3
"""Compute the outline/06 R6 and R7 numbers from a filled results sheet.

Usage:
  python3 analyze.py results.csv
  python3 analyze.py --pilot pilot.csv
  python3 analyze.py --self-test

The results sheet has:
  participant,task,condition,time_seconds,correct
participant is P01, P02, ...; condition is native or outline and must match
the order schedule (outline/06 R5, R12); correct is yes or no. Each
participant does every task once (R11).

The pilot sheet has:
  participant,task,time_seconds
participant is X01 or X02; every run uses the native diff. Each run is
labelled with the condition the order schedule would give it (X01 follows
P01's row, X02 follows P02's row), and the script reports the relative
difference between the two labels. With no outline run, that difference is
the variation from participants and tasks alone; the protocol uses it to
decide whether the 20% threshold is above that variation.
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
MINIMUM_PARTICIPANTS = 6


def _median(values: list[float]) -> float:
    if not values:
        raise ValueError("no values")
    return statistics.median(values)


def _number(participant: str, prefix: str) -> int:
    if len(participant) < 2 or participant[0] != prefix or not participant[1:].isdigit() or int(participant[1:]) < 1:
        raise ValueError(f"participant code must be {prefix}01, {prefix}02, ...: {participant!r}")
    return int(participant[1:])


def scheduled(participant_number: int, task: str) -> str:
    """The order schedule: odd participants do T1, T3 and T5 natively, even ones T2 and T4."""
    odd_task = int(task[1:]) % 2 == 1
    odd_participant = participant_number % 2 == 1
    return "native" if odd_task == odd_participant else "outline"


def _complete(rows: list[dict[str, str]]) -> set[str]:
    """Return the participants, or fail when a run is missing, repeated or off the schedule."""
    seen: set[tuple[str, str]] = set()
    participants: set[str] = set()
    for row in rows:
        participant = (row.get("participant") or "").strip()
        task = (row.get("task") or "").strip()
        if not participant or not task:
            raise ValueError("each row needs participant and task")
        if task not in TASKS:
            raise ValueError(f"unknown task: {task!r}")
        number = _number(participant, "P")
        condition = _condition(row.get("condition", ""))
        try:
            time_seconds = float(row.get("time_seconds", ""))
        except ValueError as error:
            raise ValueError(f"bad time for {participant} {task}: {row.get('time_seconds')!r}") from error
        if time_seconds <= 0:
            raise ValueError(f"time must be positive for {participant} {task}")
        _correct(row.get("correct", ""))
        if (participant, task) in seen:
            raise ValueError(f"duplicate row for {participant} {task}")
        expected = scheduled(number, task)
        if condition != expected:
            raise ValueError(f"{participant} {task} is {condition}, but the order schedule says {expected}")
        seen.add((participant, task))
        participants.add(participant)
    for participant in participants:
        for task in TASKS:
            if (participant, task) not in seen:
                raise ValueError(f"missing row for {participant} {task}")
    return participants


def analyze(rows: list[dict[str, str]]) -> int:
    if not rows:
        print("INCONCLUSIVE: the results sheet is empty.")
        return 2
    try:
        participants = _complete(rows)
    except ValueError as error:
        print(f"INCONCLUSIVE: the results sheet is incomplete: {error}")
        return 2

    times: dict[str, list[float]] = {condition: [] for condition in CONDITIONS}
    wrong: dict[str, int] = {condition: 0 for condition in CONDITIONS}
    per_task: dict[str, dict[str, list[float]]] = {task: {c: [] for c in CONDITIONS} for task in TASKS}
    per_task_wrong: dict[str, dict[str, int]] = {task: {c: 0 for c in CONDITIONS} for task in TASKS}

    for row in rows:
        task = (row.get("task") or "").strip()
        condition = _condition(row.get("condition", ""))
        time_seconds = float(row.get("time_seconds", ""))
        is_wrong = 0 if _correct(row.get("correct", "")) else 1
        times[condition].append(time_seconds)
        wrong[condition] += is_wrong
        per_task[task][condition].append(time_seconds)
        per_task_wrong[task][condition] += is_wrong

    print(f"participants: {len(participants)}")
    if len(participants) % 2 == 1:
        print(f"INCONCLUSIVE: an odd number of participants ({len(participants)}) leaves the conditions unbalanced (R12).")
        return 2

    native_median = _median(times["native"])
    outline_median = _median(times["outline"])
    reduction = (native_median - outline_median) / native_median * 100.0

    print(f"native: {len(times['native'])} runs, median {native_median:.1f} s, wrong {wrong['native']}")
    print(f"outline: {len(times['outline'])} runs, median {outline_median:.1f} s, wrong {wrong['outline']}")
    print(f"relative reduction: {reduction:.1f}% (needs at least 20.0%)")
    print()
    print("task | native median | outline median | reduction | native wrong | outline wrong")
    for task in TASKS:
        nm = _median(per_task[task]["native"])
        om = _median(per_task[task]["outline"])
        print(f"{task:>4} | {nm:13.1f} | {om:14.1f} | {(nm - om) / nm * 100:8.1f}% | "
              f"{per_task_wrong[task]['native']:12} | {per_task_wrong[task]['outline']:13}")

    print()
    if len(participants) < MINIMUM_PARTICIPANTS:
        print(f"INCONCLUSIVE: fewer than {MINIMUM_PARTICIPANTS} participants ({len(participants)}). The 20% bar is not lowered.")
        return 2
    if reduction >= 20.0 and wrong["outline"] <= wrong["native"]:
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
    by_run: dict[tuple[str, str], float] = {}
    labelled: dict[str, list[float]] = {condition: [] for condition in CONDITIONS}
    for row in rows:
        participant = (row.get("participant") or "").strip()
        task = (row.get("task") or "").strip()
        if participant not in {"X01", "X02"} or task not in TASKS:
            print(f"INCONCLUSIVE: each pilot row needs participant X01 or X02 and a task T1..T5: {participant!r} {task!r}")
            return 2
        try:
            time_seconds = float(row.get("time_seconds", ""))
        except ValueError:
            print(f"INCONCLUSIVE: bad time for {participant} {task}")
            return 2
        if time_seconds <= 0:
            print(f"INCONCLUSIVE: time must be positive for {participant} {task}")
            return 2
        if (participant, task) in by_run:
            print(f"INCONCLUSIVE: duplicate pilot row for {participant} {task}")
            return 2
        by_run[(participant, task)] = time_seconds
        labelled[scheduled(_number(participant, "X"), task)].append(time_seconds)
    for participant in ("X01", "X02"):
        for task in TASKS:
            if (participant, task) not in by_run:
                print(f"INCONCLUSIVE: missing pilot row for {participant} {task}")
                return 2
    as_native = _median(labelled["native"])
    as_outline = _median(labelled["outline"])
    difference = abs(as_native - as_outline) / min(as_native, as_outline) * 100.0
    print("pilot participants: 2")
    print(f"runs labelled native: median {as_native:.1f} s; runs labelled outline: median {as_outline:.1f} s (all native)")
    print(f"variation between the labels: {difference:.1f} percentage points")
    if difference < 10.0:
        print("PILOT OK: the 20% threshold is above the observed variation.")
        return 0
    if difference < 20.0:
        print("PILOT CAUTION: 20% is above variation but close; report the pilot with the result.")
        return 0
    print("PILOT INCONCLUSIVE: variation is at least 20 points; revise the tasks or timing before the main study.")
    return 2


def _self_test() -> int:
    def rows_for(participants: int, outline_time: float, outline_wrong_first: bool = False) -> list[dict[str, str]]:
        rows = []
        for p in range(1, participants + 1):
            for t in TASKS:
                condition = scheduled(p, t)
                time_seconds = "100" if condition == "native" else str(outline_time)
                wrong = outline_wrong_first and condition == "outline" and p == 2 and t == "T1"
                rows.append({"participant": f"P{p:02}", "task": t, "condition": condition,
                             "time_seconds": time_seconds, "correct": "no" if wrong else "yes"})
        return rows

    def expect(result: int, wanted: int, message: str) -> bool:
        if result != wanted:
            print(f"self-test failed: {message}", file=sys.stderr)
            return False
        return True

    checks = [
        expect(analyze(rows_for(6, 75)), 0, "the 25% case should pass"),
        expect(analyze(rows_for(4, 75)), 2, "four participants should be inconclusive"),
        expect(analyze(rows_for(7, 75)), 2, "an odd number of participants should be inconclusive"),
        expect(analyze([r for r in rows_for(6, 75) if not (r["participant"] == "P06" and r["task"] == "T5")]), 2,
               "a missing run should be inconclusive"),
        expect(analyze(rows_for(6, 75) + rows_for(1, 75)), 2, "a repeated run should be inconclusive"),
        expect(analyze([dict(r, condition="outline") if r["participant"] == "P01" and r["task"] == "T1" else r
                        for r in rows_for(6, 75)]), 2, "a run off the order schedule should be inconclusive"),
        expect(analyze(rows_for(6, 75, outline_wrong_first=True)), 1, "a faster but wrong outline should fail"),
        expect(analyze(rows_for(6, 90)), 1, "a 10% reduction should fail"),
    ]
    if not all(checks):
        return 1
    steady = [{"participant": p, "task": t, "time_seconds": "100" if p == "X01" else "104"}
              for p in ("X01", "X02") for t in TASKS]
    if not expect(analyze_pilot(steady), 0, "a pilot with similar participants should be ok"):
        return 1
    uneven = [{"participant": p, "task": t, "time_seconds": "100" if p == "X01" else "50"}
              for p in ("X01", "X02") for t in TASKS]
    if not expect(analyze_pilot(uneven), 2, "a pilot whose labels differ by 20 points or more should be inconclusive"):
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
