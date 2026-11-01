# Relationship benchmark

Status: proposed, 2026-11-01

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
- **R8** The cases cover renames, moves, kind changes, signature changes, extraction, inlining, overloads, duplicate bodies, formatting-only and comment-only changes, parse errors, empty files and large files.
- **R9** Each case category has at least 3 cases for each of Java, Kotlin, Scala 2 and Scala 3, where the language has the construct.
- **R10** The corpus list can also name real commits, as the [corpus validation](../../VALIDATION.md) does, with at least 30 labelled commits per language.
- **R11** The cases are split into a tuning set and a held-out set, and the report gives the held-out results separately.
- **R12** For each relationship kind, the report gives precision, recall and the share of wrong pairs, per language and overall.
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
- **R29** Before the first relationship model change, a baseline report of today's engine is recorded in VALIDATION.md.

## Out of scope

- Cross-file moves and commit-level refactorings.
- Local-variable renames and statement moves.
- Comparing SMART Diff with other products.

## References

- [Details: design and tests](01-relationship-benchmark-details.md)
- [Structural relationships plan](../../STRUCTURAL-RELATIONSHIPS.md)
- [Relationship benchmark plan](../../RELATIONSHIP-BENCHMARK.md)
- [ADR 0008](../../adr/0008-relationships-are-the-source-of-truth-for-pairing.md)
