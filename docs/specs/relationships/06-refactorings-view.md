# Refactorings view

Status: proposed, 2026-11-01

**Description:** A second view of the [structural outline](../../GLOSSARY.md#structural-outline) that lists the [relationships](../../GLOSSARY.md#relationship) of a file, such as renames, moves, extractions and inlinings, before the remaining changes.

**Use case:** A reviewer opens a large refactoring commit. The refactorings view shows `rename total → sum, 12 usages`, `extracted describeItems` and `moved parse` first, so they can check those quickly and spend their time on the changes left over.

## Requirements

- **R1** A toolbar button in the outline switches between the container tree and the [refactorings view](../../GLOSSARY.md#refactorings-view).
- **R2** The choice is remembered as a setting, and the container tree is the default.
- **R3** The refactorings view has one row per relationship, with its kinds and both names.
- **R4** A rename with grouped usages is one row, and its usages are listed under it.
- **R5** Changes in no relationship are listed under `Other changes`, as the container tree shows them.
- **R6** Every change the container tree shows is in the refactorings view.
- **R7** A row shows its [confidence](../../GLOSSARY.md#confidence) as a word, never as a percentage.
- **R8** A medium-confidence relationship shows as a [candidate](../../GLOSSARY.md#candidate), and a low-confidence one as a possible link under both its declarations.
- **R9** A row shows its [evidence](../../GLOSSARY.md#evidence) when expanded.
- **R10** Selecting a row frames both declarations and every grouped usage.
- **R11** A side a relationship does not reach shows `not present`.
- **R12** Each rename has its own highlight colour for its usages in both editors.
- **R13** The [change type filter](../../GLOSSARY.md#change-type-filter) works in both views.
- **R14** A menu hides relationships by kind.
- **R15** A relationship never hides an [uncertain](../../GLOSSARY.md#uncertain), [unanalyzed](../../GLOSSARY.md#unanalyzed) or changed declaration.
- **R16** A file with no relationships shows `No refactorings found` and then its other changes.
- **R17** The [summary line](../../GLOSSARY.md#summary-line) is the same in both views.
- **R18** The [outline toggle](../../GLOSSARY.md#outline-toggle) and the native diff work as before in both views.

## Out of scope

- The refactorings view in the merge outline and the Changes tree.
- Relationships across files.

## References

- [Details: design and tests](06-refactorings-view-details.md)
- [Structural relationships plan §10](../../STRUCTURAL-RELATIONSHIPS.md#10-viewer)
- [ADR 0008](../../adr/0008-relationships-are-the-source-of-truth-for-pairing.md)
- [Structural outline](../outline/01-structural-outline.md)
