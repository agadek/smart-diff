# Changes tree caret follow

Status: implemented, 2026-11-07

**Description:** When the caret moves in a file shown in the [Changes tree](../../GLOSSARY.md#changes-tree), the tree
expands the file and selects the innermost changed [declaration](../../GLOSSARY.md#declaration) under the caret, or the
file's row when the caret is outside every changed declaration.

**Use case:** A developer reviews Local Changes while reading or editing a file. They put the caret in a method, and the
tree opens that file and marks the method, so the tree and the editor show the same place without the developer searching
the tree. This is the reverse of opening a declaration from the tree.

## Requirements

- **R1** When the caret is inside a changed declaration of a file shown in the Changes tree, the tree expands the file
  and selects the innermost declaration containing the caret, and scrolls it into view.
- **R2** When the caret is in the file but outside every changed declaration, the tree selects the file's row and no
  declaration row.
- **R3** The caret maps to the [new side](../../GLOSSARY.md#new-side) range in a plain editor and on the new side of a
  two-sided diff, and to the [old side](../../GLOSSARY.md#old-side) range on the old side. In the platform's
  selection-following Changes preview, whether it is an editor tab or the Local Changes splitter, following selects the
  file row and never a declaration row, which would close or empty the preview.
- **R4** The setting "Follow the caret in the Changes tree" turns this on and off, and it is on by default.
- **R5** Following never moves focus out of the editor, opens a diff itself or changes what is included in the commit,
  though the platform's own single-click preview may follow the new selection as it does for any selection.
- **R6** Following does not change the tree while the tree has focus, a selection made in the tree is not overwritten by a
  later caret event, and caret moves are debounced so typing and arrow keys stay smooth.
- **R7** While a file's [summary line](../../GLOSSARY.md#summary-line) is stale after an edit, following does not select a
  row from the old summary; if the rebuilt tree shows only a plain pending file row, it selects that file row and keeps
  retrying, and it selects the row under the caret when the new summary arrives.
- **R8** A file without a summary, because it is pending, not analyzed, in an
  [unsupported language](../../GLOSSARY.md#supported-language), added, deleted or after the 500-file limit, gets only its
  file row selected.
- **R9** Following reads no revision content on the UI thread and uses no internal, experimental or deprecated platform
  API.
- **R10** When the tree's filter or its Declarations toggle hides the declaration under the caret, the tree selects the
  nearest shown row above it, or the file row when no declaration row is shown.
- **R11** A caret event from the platform's selection-following preview, or from an editor without focus, does not
  replace a pending follow from another editor.

## Out of scope

- Three-sided diffs and merge windows; the mapping returns for them without touching the tree.
- The modal commit dialog and the Git log's commit details, which have no Changes tree hook.
- Opening or focusing a diff; following only moves the tree's selection. A platform diff preview that follows the
  selection is the platform's behavior, not this feature.
- The Changes tree's own display toggles and change type filter, which are specified separately; following uses the rows
  that are shown.

## References

- [Details: design and tests](05-changes-tree-caret-follow-details.md)
- [Changes tree summary](01-changes-tree-summary.md)
- [Declarations in the Changes tree](02-changes-tree-declarations.md)
- [Settings page](../settings/01-settings-page.md)
- [ADR 0006](../../adr/0006-changes-tree-summary-through-changes-view-modifier.md)
- [Technical specification §9.4](../../TECHNICAL-SPECIFICATION.md#94-changes-tree-summary)
