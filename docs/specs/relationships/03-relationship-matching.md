# Relationship matching

Status: accepted, 2026-11-04

**Description:** Improves how the declaration engine pairs renamed, moved and kind-changed declarations, by scoring every option over the whole candidate graph and keeping ambiguous cases unpaired.

**Use case:** A reviewer opens a diff where several methods were renamed and moved at once. More of them show as one row with their old and new names, and none of them is paired with the wrong declaration.

## Requirements

- **R1** The matcher keeps today's matching rules as the first source of [candidates](../../GLOSSARY.md#candidate).
- **R2** A candidate is scored by name, content, parameter types, [container](../../GLOSSARY.md#container), nearest siblings and order.
- **R3** Source position adds to a score but never pairs two declarations on its own.
- **R4** Pairs are chosen over the whole candidate graph of the file at once, so one pair never takes a better option from another.
- **R5** A pair is taken only when its score reaches the pairing floor and beats every rival by a margin, unless the names clearly decide between close options.
- **R6** When no rename option clearly wins, the declarations stay [added](../../GLOSSARY.md#added) and [removed](../../GLOSSARY.md#removed), and each tied option is a low-[confidence](../../GLOSSARY.md#confidence) [relationship](../../GLOSSARY.md#relationship).
- **R7** Declarations with the same body in one container are never paired by content alone.
- **R8** Overloads that differ only in their parameters are paired by [signature](../../GLOSSARY.md#signature) and content, before any rename candidate is considered.
- **R9** A declaration that changed in more than one way at once gives one relationship with all the kinds that apply.
- **R10** A paired relationship's confidence follows the thresholds of the request, and a tied option is low.
- **R11** Ties in the score go to the order on the [old side](../../GLOSSARY.md#old-side), then on the [new side](../../GLOSSARY.md#new-side).
- **R12** A group with more possible pairs than the limit falls back to the greedy pass.
- **R13** Matching stops promptly when the comparison is cancelled.
- **R14** The median and slowest time per language stay within the [analysis limits](../../GLOSSARY.md#analysis-limits).
- **R15** The change passes the [benchmark](01-relationship-benchmark.md) gate `no-new-wrong-pairs` against the recorded baseline.
- **R16** Held-out precision is at least the baseline for every relationship kind and language.
- **R17** The golden fixtures keep their rows, or each changed row is reviewed and listed in the pull request.
- **R18** The engine version changes.
- **R19** Changing a confidence threshold never changes which declarations are paired.
- **R20** The default high threshold is chosen on the tuning split only.

## Out of scope

- Grouping a rename with its usages.
- Inlining.
- Changes to the outline.
- Relationships across files.
- Rename candidates across unmatched containers.

## References

- [Details: design and tests](03-relationship-matching-details.md)
- [Structural relationships plan §6](../../STRUCTURAL-RELATIONSHIPS.md#6-matching)
- [Rename candidates](../declarations/03-rename-candidates.md)
- [Relationship model](02-relationship-model.md)
- [Relationship benchmark](01-relationship-benchmark.md)
- [ADR 0003](../../adr/0003-conservative-classification.md)
- [ADR 0008](../../adr/0008-relationships-are-the-source-of-truth-for-pairing.md)
