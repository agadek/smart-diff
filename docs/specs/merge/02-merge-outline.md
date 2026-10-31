# Merge outline

Status: proposed, 2026-10-31

**Description:** A [structural outline](../../GLOSSARY.md#structural-outline) for merges, built from the [merge join](01-merge-join.md), that groups declarations by which [side](../../GLOSSARY.md#side) changed them.

**Use case:** A reviewer merges a feature branch into `main` and two declarations conflict. The outline shows first what was [changed on both sides](../../GLOSSARY.md#changed-on-both-sides), with what each side did, then what only one side changed, so the reviewer knows where to look before resolving anything.

## Requirements

- **R1** The outline opens grouped by side: changed on both sides, then each side alone, then uncertain.
- **R2** A toolbar toggle switches to the container tree, where each row carries both sides' labels.
- **R3** The chosen layout is remembered.
- **R4** A row changed on both sides shows each side's labels after that side's name: `main: changed  feature: rename candidate → sum(Order)`.
- **R5** A row in a one-side group shows only that side's labels.
- **R6** A [same change on both sides](../../GLOSSARY.md#same-change-on-both-sides) row says so and comes last in its group.
- **R7** [Uncertain](../../GLOSSARY.md#uncertain) declarations are listed in the uncertain group, with their [candidates](../../GLOSSARY.md#candidate) from that side.
- **R8** Each side is named after the branch or revision it comes from, such as `main` or a short commit hash.
- **R9** A side name never says "ours" or "theirs".
- **R10** A title in an unknown form is shown as written, cut at 20 characters.
- **R11** The full title is the side name's tooltip and part of the row's accessible name.
- **R12** The [summary line](../../GLOSSARY.md#summary-line) counts rows by group: `2 changed on both sides · 2 in main only · 1 in feature only`.
- **R13** The [change type filter](../../GLOSSARY.md#change-type-filter) applies to each side's labels.
- **R14** A row is hidden only when every label on both sides is hidden.
- **R15** In the container tree, the filter can also hide rows changed on both sides.
- **R16** Selecting a row frames the declaration on the left, on the [base](../../GLOSSARY.md#base) or result, and on the right.
- **R17** A pane where the declaration is absent shows "not present".
- **R18** The [engine dropdown](../../GLOSSARY.md#engine-dropdown), the refresh action and the [outline toggle](../../GLOSSARY.md#outline-toggle) work as in a two-sided diff.
- **R19** The outline opens when "Show structural outline automatically" is on, with no separate setting.
- **R20** The analysis stops when both comparisons together take longer than the 5 s [analysis limit](../../GLOSSARY.md#analysis-limits).
- **R21** When the join reports that the base was read differently, the outline shows each side as its own list with the banner "the base was read differently for each side".
- **R22** When one side cannot be analyzed, the outline shows that side's status and reason, and still lists the other side's changes.
- **R23** The outline works for Java, Kotlin, Scala 2 and Scala 3.
- **R24** The outline never claims that a merge is safe, and never edits the result.

## Out of scope

- Resolving, applying or combining changes.
- Marking which conflicts are already resolved.

## References

- [Three-way merge plan §4 and §10](../../THREE-WAY-MERGE.md#4-behaviour)
- [ADR 0003](../../adr/0003-conservative-classification.md)
