# Relationship model

Status: implemented, 2026-11-04

**Description:** Adds a list of [relationships](../../GLOSSARY.md#relationship) to every comparison result: which old declarations and new declarations belong together, how sure the engine is, and why.

**Use case:** A developer improving the matcher wants to see why two declarations were paired as a rename. The result carries the pair, its [confidence](../../GLOSSARY.md#confidence) and its [evidence](../../GLOSSARY.md#evidence), and the outline still shows exactly what it shows today.

## Requirements

- **R1** A comparison result has a list of relationships, empty by default.
- **R2** A relationship links one old-side declaration to one new-side declaration, or one declaration to several on the other side.
- **R3** A relationship has one or more kinds: rename, move, kind change, extraction or inlining.
- **R4** A relationship has a confidence of high, medium or low.
- **R5** A relationship has a list of evidence: name, content, structure, position or score; only content evidence carries the old and new ranges it compares, and name, structure, position and score evidence carry no ranges.
- **R6** The engine's numeric score is kept in the evidence and is never shown as a percentage.
- **R7** The list of relationships is the only source for renames, moves, kind changes and extraction links.
- **R8** The rename, move, kind change and extraction fields of a change are computed from the relationships.
- **R9** With today's matcher, the outline, the Changes tree and the merge outline show the same rows and labels as before.
- **R10** The golden fixtures pass unchanged.
- **R11** A high-confidence relationship pairs its declarations.
- **R12** A medium-confidence relationship still pairs its declarations, not as one added and one removed.
- **R13** A low-confidence relationship leaves both declarations added and removed, and its containers count as unmatched for their members.
- **R14** The thresholds for each confidence level are set in the request, as the [analysis limits](../../GLOSSARY.md#analysis-limits) are.
- **R15** A registry key sets the thresholds in a running IDE, and tests and the benchmark set them directly.
- **R16** Every endpoint names a declaration that appears in the result tree.
- **R17** Every endpoint and content evidence range lies inside its side's text.
- **R18** The extraction fields of a source and of its target come from the same relationship, so they always agree.
- **R19** Relationships, their kinds and their evidence come in a fixed order, so two runs give equal results.
- **R20** A relationship never hides a difference: the nothing-hidden rule holds with or without it.
- **R21** A result that is not analyzed or partly analyzed has relationships only for declarations it analyzed.
- **R22** An engine that does not detect relationships returns an empty list and still passes the engine contract.
- **R23** The engine contract checks R16 to R20 for every engine.
- **R24** The engine version changes when the matcher or a parser that can change the relationships changes, the Scala plugin included.
- **R25** With today's matcher, a rename candidate is high when its score reaches the high threshold and medium otherwise; a move, a kind change and an extraction have high confidence.
- **R26** A declaration that is renamed and reordered, or changed in kind and reordered, has one relationship with all its kinds, at its lowest kind's confidence.
- **R27** An extraction has one relationship for each source and target, with the extracted code as its content evidence.
- **R28** The outline labels every paired rename as a [rename candidate](../../GLOSSARY.md#rename-candidate), whatever its confidence.
- **R29** An [uncertain](../../GLOSSARY.md#uncertain) declaration keeps its candidates, and they are not relationships.
- **R30** A malformed registry value leaves the default thresholds in place.
- **R31** The [relationship benchmark](01-relationship-benchmark.md) reads the result's high and medium relationships instead of deriving them from today's fields; a low relationship is not scored as a pair.
- **R32** The benchmark counts a repeated relationship as a wrong pair.
- **R33** The benchmark report counts the reported relationships of each kind by confidence.

## Out of scope

- A refactorings view in the outline.
- Grouping a rename with its usages, which needs a reference analysis pass.
- Changes to how the matcher pairs declarations.
- A label for a high-confidence rename other than rename candidate.
- Scoring a label's confidence in the benchmark.
- Cross-file relationships.

## References

- [Details: design and tests](02-relationship-model-details.md)
- [ADR 0008](../../adr/0008-relationships-are-the-source-of-truth-for-pairing.md)
- [Structural relationships plan §5 and §9](../../STRUCTURAL-RELATIONSHIPS.md)
- [Relationship benchmark baseline, 2026-11-02](../../VALIDATION.md#2026-11-02-relationship-benchmark-baseline)
- [Engine contract](../engines/02-engine-contract.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
