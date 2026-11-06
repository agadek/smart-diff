# Declarations in the Changes tree

Status: implemented, 2026-11-06

**Description:** A file with a summary in the [Changes tree](../../GLOSSARY.md#changes-tree) can be expanded to show its changed [declarations](../../GLOSSARY.md#declaration), the same rows as its [structural outline](../../GLOSSARY.md#structural-outline).

**Use case:** Before committing, a developer expands a file in Local Changes, sees which methods changed, and opens one straight in its diff.

## Requirements

- **R1** A file with a summary can be expanded to show the rows of its outline.
- **R2** A file that shows a reason instead of a summary has no rows.
- **R3** The rows have the same nesting, groups and [labels](../../GLOSSARY.md#label) as the outline, and look the same.
- **R4** The outline's [change type filter](../../GLOSSARY.md#change-type-filter) does not apply to these rows; the tree has its own filter ([changes-tree/04](04-hide-and-filter.md)).
- **R5** The rows are computed with the summary and follow edits in the same way.
- **R6** A file starts collapsed, and so do the containers inside it.
- **R7** The tree keeps the rows the user expanded when it is rebuilt.
- **R8** Declaration rows are not changes: they are not counted, included or selected for a commit.
- **R9** Enter on a declaration row opens the file's diff with that declaration selected in the outline.
- **R10** Double-click on a declaration row without children opens it the same way. *(retired)*
- **R11** Double-click on a declaration row with children expands it. *(retired)*
- **R12** When the outline's filter hides the declaration, the nearest row above it is selected.
- **R13** The outline is shown in that diff even if it starts off by default.
- **R14** Double-click on a declaration row opens it the same way, whether or not it has children.
- **R15** A declaration row with children expands through the tree's expansion control.
- **R16** When the file's diff with the declaration selected cannot be opened, the row opens the file's diff without a declaration selected instead.
- **R17** Double-click on a file row with a summary opens it as a file without a summary does.
- **R18** Enter on a file row with a summary opens it as a file without a summary does.
- **R19** The diff preview shows a file row with a summary as it does a file without a summary.
- **R20** A file row with a summary expands and collapses through the tree's expansion control.

## References

- [Hide and filter in the Changes tree](04-hide-and-filter.md)
- [ADR 0006](../../adr/0006-changes-tree-summary-through-changes-view-modifier.md)
- [Technical specification §9.4](../../TECHNICAL-SPECIFICATION.md#94-changes-tree-summary)
