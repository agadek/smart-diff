# Reviewed marks

Status: implemented, 2026-11-16

**Description:** The reviewer can mark a change in the [structural outline](../../GLOSSARY.md#structural-outline) as [reviewed](../../GLOSSARY.md#reviewed-mark), and the [summary line](../../GLOSSARY.md#summary-line) shows how many of the file's changes are reviewed. A mark is dropped when that [declaration](../../GLOSSARY.md#declaration) changes again.

**Use case:** A reviewer works through a large change over several sittings. They mark each declaration once they have read it, see `5 of 8 reviewed` in the summary line, and when the author pushes a fix, only the declarations the fix touched lose their marks and need a second look.

## Requirements

- **R1** A change row and the [file region](../../GLOSSARY.md#file-region) row of the two-sided outline can be marked reviewed and unmarked again.
- **R2** "Mark as Reviewed" and "Unmark Reviewed" are in the outline's context menu, and Space toggles the mark of the selected row.
- **R3** Marking a row with change rows under it, or a group such as `Formatting-only (3)`, marks those change rows too, and unmarking it unmarks them.
- **R4** A reviewed row shows the label `reviewed` after its other [labels](../../GLOSSARY.md#label), and its title is greyed.
- **R5** A container shown only because of changes under it shows `reviewed` when every change row under it is reviewed.
- **R6** While at least one change of the file is reviewed, the summary line ends with `N of M reviewed`, where M counts every reported change of the file, hidden or not.
- **R7** With no change of the file reviewed, the outline and the summary line are the same as without this feature.
- **R8** A mark stays while the declaration's text on both sides is the same as when it was marked, and is dropped once the outline shows it with different text on either side.
- **R9** Marks are kept per project when the diff is closed, when the file's other changes are edited and when the IDE restarts.
- **R10** A diff whose file has no path in the project keeps its marks only while it is open.
- **R11** "Clear Reviewed Marks" in the context menu unmarks every change of the file.
- **R12** "Hide Reviewed" in the [change type filter](../../GLOSSARY.md#change-type-filter) menu is off by default, and with it on the outline leaves out reviewed changes as it does a hidden [change type](../../GLOSSARY.md#change-type).
- **R13** The [refactorings view](../../GLOSSARY.md#refactorings-view) shows the same marks, and marking a relationship row marks every declaration it links.
- **R14** Marking or unmarking a row never compares the file again, keeps the selection, and updates every open outline of the same file.
- **R15** Marks never change how a declaration is matched, classified or labelled, and are never sent outside the IDE.

## Out of scope

- The Changes tree, its summary and its declaration rows.
- The merge outline and [three-sided diffs](../../GLOSSARY.md#three-sided-diff).
- Sharing marks between people or with a pull request service.
- Marking a whole file as reviewed from the Changes tree.

## References

- [Details: design and tests](12-reviewed-marks-details.md)
- [Change type filter](03-change-type-filter.md)
- [Refactorings view](../relationships/06-refactorings-view.md)
- [Product scope](../README.md#product-scope) P1, P3 and P8
- [Roadmap: Reviewed marks in the outline](../../ROADMAP.md#reviewed-marks-in-the-outline)
