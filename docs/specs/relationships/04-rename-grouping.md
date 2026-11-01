# Rename grouping

Status: proposed, 2026-11-01

**Description:** Groups a renamed declaration with the [usages](../../GLOSSARY.md#usage) in the same file that changed only to follow the rename, so the reviewer reads the rename once.

**Use case:** A reviewer opens a diff where a method was renamed and twelve calls to it were updated. The outline shows `rename total → sum, 12 usages` once, and selecting it frames every call, instead of twelve separate changes to read.

## Requirements

- **R1** A reference pass runs after the [comparison engine](../../GLOSSARY.md#comparison-engine) and adds usages to its rename [relationships](../../GLOSSARY.md#relationship).
- **R2** The engine's result is shown first, and the pass updates it when it finishes.
- **R3** The pass runs only when the diff's file belongs to the open project, and otherwise the result stays the engine's.
- **R4** Usages are grouped only for a rename with high [confidence](../../GLOSSARY.md#confidence).
- **R5** A usage is grouped only when it resolves to the renamed declaration on both sides.
- **R6** A usage that does not resolve, or resolves to another declaration, stays an ordinary change.
- **R7** A usage is grouped only when the rename is its only change.
- **R8** A grouped usage still counts as a change, and grouping never hides it.
- **R9** The rename's [label](../../GLOSSARY.md#label) gives the number of grouped usages: `rename total → sum, 12 usages`.
- **R10** A declaration whose only changes are grouped usages is labelled `usages renamed`.
- **R11** Selecting the rename frames every grouped usage on both sides.
- **R12** Each grouped usage keeps the symbol it resolves to and both its ranges as [evidence](../../GLOSSARY.md#evidence).
- **R13** Only usages in the compared file are grouped.
- **R14** The pass covers Java, Kotlin and Scala, with one resolver per language.
- **R15** The Kotlin and Scala resolvers are reviewed in the Plugin Verifier report for both target IDEs.
- **R16** The pass stops promptly when the comparison is cancelled.
- **R17** The pass adds at most 1 s per file, and past that the result has no grouped usages.
- **R18** The benchmark labels usages, and the pass adds no wrongly grouped usage against the baseline.

## Out of scope

- Renames of local variables and parameters.
- Usages in other files.
- A colour per rename in the editors, which comes with the [refactorings view](06-refactorings-view.md).

## References

- [Details: design and tests](04-rename-grouping-details.md)
- [Structural relationships plan §7](../../STRUCTURAL-RELATIONSHIPS.md#7-reference-aware-rename-grouping)
- [ADR 0008](../../adr/0008-relationships-are-the-source-of-truth-for-pairing.md)
- [ADR 0002](../../adr/0002-parser-neutral-model-with-psi-first.md)
