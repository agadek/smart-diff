# Relationship matching

Status: proposed, 2026-11-01

**Description:** Improves how the declaration engine pairs renamed, moved and kind-changed declarations, by scoring every option over the whole file and keeping ambiguous cases unpaired.

**Use case:** A reviewer opens a diff where several methods were renamed and moved at once. More of them show as one row with their old and new names, and none of them is paired with the wrong declaration.

## Requirements

- **R1** The matcher keeps today's matching rules as the first source of [candidates](../../GLOSSARY.md#candidate).
- **R2** A candidate is scored by name, content, parameter types, [container](../../GLOSSARY.md#container), nearest siblings and order.
- **R3** Source position adds to a score but never pairs two declarations on its own.
- **R4** Pairs are chosen over the whole file at once, so one pair never takes a better option from another.
- **R5** A pair is taken only when its score is above a minimum and ahead of every rival by a margin.
- **R6** When no option clearly wins, both declarations stay unpaired, with a low-[confidence](../../GLOSSARY.md#confidence) [relationship](../../GLOSSARY.md#relationship) for each option.
- **R7** Declarations with the same body in one container are never paired by content alone.
- **R8** Overloads that differ only in parameter types are paired by name and [signature](../../GLOSSARY.md#signature) before content.
- **R9** A declaration renamed, moved and changed in kind and signature at once gives one relationship with all its kinds.
- **R10** Each relationship's confidence follows the thresholds of the request.
- **R11** Ties go to the order on the [old side](../../GLOSSARY.md#old-side), then on the [new side](../../GLOSSARY.md#new-side).
- **R12** A group of candidates larger than the limit is paired as it is today.
- **R13** Matching stops promptly when the comparison is cancelled.
- **R14** The median and slowest time per language stay within the [analysis limits](../../GLOSSARY.md#analysis-limits).
- **R15** The change passes the [benchmark](01-relationship-benchmark.md) gate `no-new-wrong-pairs` against the recorded baseline.
- **R16** Held-out precision is at least the baseline for every relationship kind and language.
- **R17** The golden fixtures keep their rows, or each changed row is reviewed and listed in the pull request.
- **R18** The engine version changes.

## Out of scope

- Grouping a rename with its usages.
- Inlining.
- Changes to the outline.
- Relationships across files.

## References

- [Details: design and tests](03-relationship-matching-details.md)
- [Structural relationships plan §6](../../STRUCTURAL-RELATIONSHIPS.md#6-matching)
- [Rename candidates](../declarations/03-rename-candidates.md)
- [Relationship model](02-relationship-model.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
