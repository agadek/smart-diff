# Rename candidates

Status: implemented, 2026-10-22

**Description:** A [removed](../../GLOSSARY.md#removed) and an [added](../../GLOSSARY.md#added) declaration of the same kind with similar content are shown once, as a [rename candidate](../../GLOSSARY.md#rename-candidate).

**Use case:** A reviewer opens a diff where a class was renamed. They see one row, `class Order → PurchaseOrder`, with its members in place, instead of a whole class removed and another added.

## Requirements

- **R1** A removed and an added declaration of the same [kind](../../GLOSSARY.md#kind) in matched [containers](../../GLOSSARY.md#container), or both top level, can be paired as a rename candidate.
- **R2** They are paired when their content, without their names, is at least 70% similar.
- **R3** A pair is taken only when it is clearly more similar than every other option of both declarations.
- **R4** When two options are about as similar, the names decide, and if they do not, nothing is paired.
- **R5** A declaration with fewer than 6 tokens besides its name is never paired by content.
- **R6** A rename candidate's label shows the old name first: `method total → sum(int)`.
- **R7** A rename alone leaves the content unchanged.
- **R8** The members of a renamed class are matched inside it and are not moved.
- **R9** A Java constructor follows its class's rename.
- **R10** The [change type filter](../../GLOSSARY.md#change-type-filter) never hides a rename candidate.

## Out of scope

- Renames of local variables.
- Grouping a rename with the usages it changed.

## References

- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Technical specification §7, rule M5](../../TECHNICAL-SPECIFICATION.md#7-declaration-engine-matching)
- [Validation, 0.1.0 noise pass](../../VALIDATION.md#2026-10-22-010-noise-pass)
