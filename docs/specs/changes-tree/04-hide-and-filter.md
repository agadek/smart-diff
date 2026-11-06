# Hide and filter in the Changes tree

Status: proposed, 2026-11-05

**Description:** The [Changes tree](../../GLOSSARY.md#changes-tree) has its own View Options toggles for the [summary line](../../GLOSSARY.md#summary-line) and the [declaration](../../GLOSSARY.md#declaration) rows, and its own [change type filter](../../GLOSSARY.md#change-type-filter) for the declaration rows, separate from the outline's.

**Use case:** Before committing, a developer wants the platform's compact file list without the declaration rows, or wants to hide formatting-only and moved changes so only the edits they care about remain. They switch the rows off, or set a filter, from the tree's View Options menu and the tree changes at once, without another comparison.

## Requirements

- **R1** The Changes tree's View Options menu, the eye icon, has the SMART Diff actions, or the tree's toolbar has them when the menu cannot be extended through public API.
- **R2** The actions include two independent toggles, "Structural Summary" and "Declarations".
- **R3** "Structural Summary" off hides the grey text after each file name, whether that text is a summary or a reason.
- **R4** "Declarations" off hides the declaration rows, and each file becomes a leaf that behaves as the platform's row.
- **R5** Each toggle changes the same setting as the Settings page, so either place changes both, and the value is remembered.
- **R6** Changing a toggle rebuilds the tree without comparing a file again.
- **R7** The declaration rows can hide changed, added, removed, moved and formatting-only changes.
- **R8** Hiding changed also hides signature changes.
- **R9** A change is hidden only when all of its labels are hidden.
- **R10** A hidden change with shown changes inside it stays as a container without labels.
- **R11** Uncertain, [rename candidate](../../GLOSSARY.md#rename-candidate) and [unanalyzed](../../GLOSSARY.md#unanalyzed) changes are never hidden.
- **R12** The tree's filter is set from the same menu as the toggles, in a "Hide Change Types" submenu.
- **R13** The summary still counts every change and, when the filter hides some, ends with how many are hidden, such as `· 2 hidden`.
- **R14** A file whose every change is hidden stays in the tree, with no declaration rows, and its summary, when shown, ends with `· all hidden`.
- **R15** Speed search in the tree finds declaration rows by their labels.
- **R16** Changing the tree's filter never changes the outline's filter.
- **R17** The tree's filter is remembered separately from the outline's, and starts with nothing hidden.
- **R18** Changing the filter rebuilds the tree without comparing a file again.

## Out of scope

- Filtering the file rows, or hiding a file from the tree. The filter applies to declaration rows only.
- The outline's change type filter and the "Hide from the outline" settings, which keep their own behavior.
- The modal commit dialog and the Git log's commit details, which have no hook for the tree.
- Following the caret into the tree (issue #110) and opening a file row with double-click or Enter (issue #111), which are separate features.

## References

- [Change type filter in the outline](../outline/03-change-type-filter.md)
- [Settings page](../settings/01-settings-page.md)
- [Technical specification §9.4](../../TECHNICAL-SPECIFICATION.md#94-changes-tree-summary)
- [ADR 0006](../../adr/0006-changes-tree-summary-through-changes-view-modifier.md)
- [Issue #112](https://github.com/agadek/smart-diff/issues/112)
- [Issue #111](https://github.com/agadek/smart-diff/issues/111)
- [Issue #110](https://github.com/agadek/smart-diff/issues/110)
