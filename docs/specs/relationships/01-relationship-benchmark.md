# Relationship benchmark

Status: implemented, 2026-11-02

**Description:** Measures how well the declaration engine pairs declarations across two versions, against labelled ground truth, so a matcher change is accepted or rejected by its numbers.

**Use case:** Before changing how the engine pairs renamed or moved declarations, a developer runs the benchmark on the current engine and records the baseline. After the change they run it again, and the report shows whether precision, recall, ambiguity and speed got better or worse.

## Requirements

- **R1** `RelationshipBenchmarkTest` runs with `-Psmartdiff.relationships.corpus=<list>`, and is skipped without it, so CI does not need the corpus.
- **R2** Each case is an old and a new file with a label file beside them, in `src/test/testData/<language>/relationships/<case>/`.
- **R3** A label lists the expected [relationships](../../GLOSSARY.md#relationship), the expected classification of each [declaration](../../GLOSSARY.md#declaration), and the cases expected to stay [uncertain](../../GLOSSARY.md#uncertain).
- **R4** Labels are YAML.
- **R5** An endpoint in a label is named by its path, [kind](../../GLOSSARY.md#kind), name and [signature](../../GLOSSARY.md#signature), as the outline names it.
- **R6** A range is added to an endpoint only to tell apart declarations with the same name.
- **R7** A label can say that a case is ambiguous, and that no relationship is correct.
- **R8** The cases cover renames, grouped usages, moves, kind changes, signature changes, extraction, inlining, overloads, duplicate bodies, formatting-only and comment-only changes, parse errors, empty files and large files.
- **R9** Each case category has at least 3 cases for each of Java, Kotlin, Scala 2 and Scala 3, where the language has the construct.
- **R10** The corpus list can also name real commits, as the [corpus validation](../../VALIDATION.md) does, with at least 30 labelled commits per language.
- **R11** The cases are split into a tuning set and a held-out set, and the report gives the held-out results separately.
- **R12** For each relationship kind, the report gives precision, recall and the share of wrong pairs, per language and overall, and `n/a` in place of a precision or recall with nothing to divide.
- **R13** A reported relationship with the right name but the wrong declaration counts as wrong.
- **R14** The report counts the ambiguous cases that were forced into a pair.
- **R15** The report gives the accuracy of each declaration's presence and content classification.
- **R16** The report gives the median and slowest time per language, against the 5 s [analysis limit](../../GLOSSARY.md#analysis-limits).
- **R17** The report counts files that broke the nothing-hidden rule, and files that fell back to the native diff.
- **R18** The report lists every wrong and missed relationship for review.
- **R19** The report records the engine version, the IDE build, the Scala plugin version and the corpus revision.
- **R20** The report goes to `build/reports/smartdiff-relationships/` as `summary.tsv`, `report.md` and `report.json`.
- **R21** A run can be compared with a saved baseline report.
- **R22** The gate is chosen with `-Psmartdiff.relationships.gate=<mode>`: `no-new-wrong-pairs` by default, `precision`, or `tolerance`.
- **R23** In `no-new-wrong-pairs`, the run fails when it has a wrong or forced pair the baseline does not have.
- **R24** In `precision`, the run fails when precision falls below the baseline.
- **R25** In `tolerance`, the run fails when precision or recall falls more than `-Psmartdiff.relationships.tolerance=<points>` below the baseline.
- **R26** In every mode, the run fails on a nothing-hidden violation, a file over the time limit, or malformed input that throws.
- **R27** The report records the gate mode used.
- **R28** Two runs on the same engine and cases give the same report, apart from times.
- **R29** Before the first relationship model change, a baseline report of today's engine is recorded in VALIDATION.md, with its `report.json` checked in beside the tests.
- **R30** Every run checks the minimums of R8, R9 and R10, and the report lists each category and language below its minimum.
- **R31** A run below any minimum is reported as a [smoke run](../../GLOSSARY.md#smoke-run).
- **R32** A smoke run's report is refused as a baseline, and a smoke run never meets R29.
- **R33** Scala 2 and Scala 3 are separate languages in every count, metric, minimum and report row.
- **R34** A real-commit line is run: every modified Java, Kotlin and Scala file it selects is compared with its first parent, and added and deleted files are listed as skipped.
- **R35** A label for a real commit names the repository, the commit and the file path, in place of old and new files.
- **R36** A file selected by a real-commit line with no reviewed label is counted and listed as unlabelled, and is not scored.
- **R37** The benchmark can write a draft label for an unlabelled file, and a draft is not scored until it is marked reviewed.
- **R38** A reported relationship whose endpoints match a label is a true positive for each kind both list, a false positive for each kind only the report lists, and a false negative for each kind only the label lists.
- **R39** A [forced pair](../../GLOSSARY.md#forced-pair) counts as forced, not as a wrong pair, and is left out of precision.
- **R40** A baseline path that does not exist fails the run.
- **R41** The `precision` and `tolerance` gates compare each relationship kind in each language on its own, and the report lists the kinds and languages the baseline lacks.
- **R42** The report lists every declaration whose presence or content classification differs from its label, with both values.
- **R43** The corpus revision changes when any case file or label changes.
- **R44** A rename label may list the usages it groups with both ranges, and a wrongly grouped usage fails the gate as a wrong pair.

## Out of scope

- Cross-file moves and commit-level refactorings.
- Local-variable renames and statement moves.
- Comparing SMART Diff with other products.

## References

- [Details: design and tests](01-relationship-benchmark-details.md)
- [Structural relationships plan](../../STRUCTURAL-RELATIONSHIPS.md)
- [Relationship benchmark plan](../../RELATIONSHIP-BENCHMARK.md)
- [ADR 0008](../../adr/0008-relationships-are-the-source-of-truth-for-pairing.md)
