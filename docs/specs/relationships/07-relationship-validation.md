# Relationship validation

Status: proposed, 2026-11-01

**Description:** The checks a release with relationships passes before it ships: the benchmark, real commits, and the existing suites.

**Use case:** Before releasing better pairing and the refactorings view, the maintainer runs the release checks. The results in VALIDATION.md show that the release pairs more declarations than the last one and adds no wrong pairs or hidden changes.

## Requirements

- **R1** The [benchmark](01-relationship-benchmark.md) runs on all its cases and real commits, with the held-out results reported.
- **R2** High-[confidence](../../GLOSSARY.md#confidence) [relationships](../../GLOSSARY.md#relationship) have at least 95% precision on the held-out set, for every kind and language.
- **R3** No ambiguous case in the benchmark is forced into a pair.
- **R4** The corpus validation on the real commits in VALIDATION.md has no wrong link or hidden change that the previous release did not have.
- **R5** The [merge validation](../merge/06-merge-validation.md) has no new `join-mismatch` file.
- **R6** Every analyzed file passes the nothing-hidden check.
- **R7** The median and slowest time per language stay within the [analysis limits](../../GLOSSARY.md#analysis-limits), with the reference pass included.
- **R8** The golden fixtures and the engine contract suite pass.
- **R9** Plugin Verifier passes on both target IDEs with the Scala plugin, and every internal API use is reviewed.
- **R10** Every wrong relationship found is fixed or listed as a known issue in VALIDATION.md.
- **R11** The results are in a dated section of VALIDATION.md, with the engine version, IDE build, Scala plugin version and corpus revision.
- **R12** The release follows RELEASING.md.

## Out of scope

- Comparing SMART Diff with other products.
- Relationships across files.

## References

- [Details: design and tests](07-relationship-validation-details.md)
- [Structural relationships plan §11 to §13](../../STRUCTURAL-RELATIONSHIPS.md#11-benchmark-and-release-gates)
- [Relationship benchmark plan](../../RELATIONSHIP-BENCHMARK.md)
- [VALIDATION.md](../../VALIDATION.md)
- [RELEASING.md](../../RELEASING.md)
