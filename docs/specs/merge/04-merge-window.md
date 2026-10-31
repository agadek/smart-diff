# Merge outline in the merge window

Status: proposed, 2026-10-31

**Description:** Shows the [merge outline](02-merge-outline.md) in the [merge window](../../GLOSSARY.md#merge-window), and keeps its rows pointing at the right code in the [result pane](../../GLOSSARY.md#result-pane) while the reviewer edits it.

**Use case:** A rebase stops on a conflict in `OrderService.java`. The merge window opens with the outline beside it. The reviewer selects `validate()`, sees both sides' versions and the result at once, and edits the result.

## Requirements

- **R1** The outline appears in the merge window for a text merge whose three versions are in one language with an engine.
- **R2** The platform's own merge viewer stays the window's viewer, so its actions keep working.
- **R3** When the outline cannot attach, the merge window shows as usual.
- **R4** The [outline toggle](../../GLOSSARY.md#outline-toggle) is in the merge window's toolbar.
- **R5** In a Git merge or cherry-pick, each [side](../../GLOSSARY.md#side) is named after the branch shown above its pane.
- **R6** When no pane title names a branch, the side is named from the window's content title.
- **R7** The outline is computed once from the [base](../../GLOSSARY.md#base) and the two sides, and edits to the result do not change it.
- **R8** Selecting a row frames the declaration in the result pane where the base declaration now is.
- **R9** The frame follows the reviewer's edits to the result.
- **R10** A declaration whose code the edits removed shows "not tracked" when selected.
- **R11** When the result no longer equals the base on opening, declarations inside changed lines show "not tracked".
- **R12** A row changed on both sides with no conflict inside it is marked [merged without conflict](../../GLOSSARY.md#merged-without-conflict).
- **R13** Rows merged without conflict come after the rows with conflicts in their group.
- **R14** When the conflicts cannot be read, the note is left out.
- **R15** Enter moves the caret into the result pane.
- **R16** The outline never edits the result pane.
- **R17** Closing the merge window releases the outline and all its text.

## Out of scope

- Conflicted files in the Changes tree.
- Comparing the result with the base or the sides.

## References

- [Three-way merge plan §6.2 and §6.3](../../THREE-WAY-MERGE.md#62-the-merge-window)
- [Spike S6 results](../../THREE-WAY-MERGE.md#12-spike-s6-results)
- [ADR 0007](../../adr/0007-merge-outline-attaches-through-a-delegating-merge-tool.md)
