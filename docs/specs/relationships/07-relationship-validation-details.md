# Relationship validation: details

Status: proposed, 2026-11-01

Feature: [Relationship validation](07-relationship-validation.md)

## Design

### Order of the checks

1. `./gradlew clean buildPlugin check` and `./gradlew verifyPlugin`, as for every pull request.
2. `RelationshipBenchmarkTest` on all cases and real commits, with the gate `no-new-wrong-pairs` against the baseline in VALIDATION.md.
3. `CorpusValidationTest` on the repositories in VALIDATION.md, compared with the previous release's results.
4. `CorpusValidationTest` merge mode ([merge/06](../merge/06-merge-validation.md)).
5. `LimitsBenchmarkTest`, with the reference pass on.
6. A manual check in the IDE: one rename with usages, one extraction and one inlining, in each language, in both outline views.

### What counts as a new wrong link

A link in the corpus validation is wrong when a reviewer marks it so in the report. A wrong link is new when the previous release's report for the same commit does not have it.

### Recording

A dated VALIDATION.md section has:

- the engine version, IDE build, Scala plugin version and corpus revision;
- the benchmark's held-out precision and recall per kind and language, next to the baseline;
- the forced-pair count;
- the corpus validation flags, next to the previous release;
- the median and slowest times per language;
- every wrong link found, with its fix or as a known issue.

### Thresholds and reasons

- At least 95% held-out precision for high confidence: a high-confidence relationship pairs declarations without a candidate mark, so a wrong one misleads the reviewer. Proposed; to confirm against the baseline.

## Tests

| Requirement | Check |
| --- | --- |
| R1–R3 | The benchmark report |
| R4 | The corpus validation report, compared by hand with the previous one |
| R5 | The merge validation report |
| R6 | `NothingHidden` over the corpus |
| R7 | `LimitsBenchmarkTest` |
| R8, R9 | CI and the verifier report |
| R10, R11 | The VALIDATION.md section |
| R12 | RELEASING.md checklist |
