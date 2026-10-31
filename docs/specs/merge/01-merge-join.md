# Merge join

Status: implemented, 2026-10-31

**Description:** Compares the [base](../../GLOSSARY.md#base) with each [side](../../GLOSSARY.md#side) of a merge and joins the two results into one list of rows, each saying what the left and the right did to a [declaration](../../GLOSSARY.md#declaration).

**Use case:** A reviewer resolves a merge conflict in `OrderService.java`. Before reading any hunk, the plugin already knows that `validate()` was changed on both sides and `parse(String)` only on one, so the outline can show that.

## Requirements

- **R1** The join runs the selected [comparison engine](../../GLOSSARY.md#comparison-engine) twice: base to left and base to right.
- **R2** Both runs use the same engine, with no change to the engine interface.
- **R3** Both runs read the base the same way, including the same Scala version, chosen from all three texts.
- **R4** A base declaration is paired across the two results by its range in the base.
- **R5** A side changes a declaration when it changes the declaration itself, not only declarations inside it.
- **R6** A declaration that each side changes is [changed on both sides](../../GLOSSARY.md#changed-on-both-sides).
- **R7** A declaration that only one side changes is changed on that side only.
- **R8** Removed on one side and changed on the other is changed on both sides.
- **R9** A rename on one side and a change on the other is changed on both sides.
- **R10** Declarations added on both sides with the same kind, container, name and signature are paired and changed on both sides.
- **R11** Added declarations whose key repeats on a side are not paired, and stay with their side.
- **R12** A pair whose text is the same on the left and the right is the [same change on both sides](../../GLOSSARY.md#same-change-on-both-sides).
- **R13** A declaration [uncertain](../../GLOSSARY.md#uncertain) on either side is uncertain, and the other side never resolves it.
- **R14** The [file regions](../../GLOSSARY.md#file-region) of both sides are paired.
- **R15** Extraction and rename links stay with the side that made them.
- **R16** Rows follow the base's nesting and order, and added rows follow under their container.
- **R17** Rows changed on neither side and holding no changed row are left out.
- **R18** Every change either side reports appears in exactly one row.
- **R19** When the two results do not agree on the base, the join reports a mismatch and gives no rows.
- **R20** When one side cannot be analyzed, the join keeps its status and reason, and still gives the other side's rows.
- **R21** Both runs run at the same time, and cancelling one cancels the other.
- **R22** The same three texts always give the same rows.

## Out of scope

- Comparing the left with the right to tell formatting differences between them.
- Effects across files, such as a method one side renamed and the other side calls.

## References

- [Three-way merge plan §5](../../THREE-WAY-MERGE.md#5-the-join)
- [ADR 0003](../../adr/0003-conservative-classification.md)
