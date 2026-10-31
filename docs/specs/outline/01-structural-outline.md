# Structural outline

Status: implemented, 2026-10-22

**Description:** A [structural outline](../../GLOSSARY.md#structural-outline) beside the [native diff](../../GLOSSARY.md#native-diff) lists the [declarations](../../GLOSSARY.md#declaration) that changed in the file, with a [label](../../GLOSSARY.md#label) for how each changed.

**Use case:** A reviewer opens the diff of a file with many hunks. The outline tells them which methods changed and how, and a click shows each one on both sides of the diff.

## Requirements

- **R1** The outline opens beside the native side-by-side diff of a file whose language has a [comparison engine](../../GLOSSARY.md#comparison-engine).
- **R2** It works in diffs of local changes, of VCS history, and from "Compare with…" actions.
- **R3** The native diff keeps working as usual, including editing the [new side](../../GLOSSARY.md#new-side).
- **R4** When the outline cannot attach to a diff, the native diff shows alone.
- **R5** A diff whose two sides are in the same language with no engine shows the outline with "No comparison engine supports <language> files. The native diff shows every change."
- **R6** A plain text diff with no engine gets no outline.
- **R7** The outline shows only reported changes and the containers that hold them.
- **R8** A container shown only for its children has no label.
- **R9** A row shows the kind, receiver, name and signature, such as `method parse(String,int)`.
- **R10** The labels are added, removed, changed, moved, signature changed, formatting-only, rename candidate, uncertain and unanalyzed.
- **R11** The outline starts with a [summary line](../../GLOSSARY.md#summary-line), such as `3 changed · 1 added · 2 removed`.
- **R12** A file with no reported changes shows "No structural changes".
- **R13** [Formatting-only](../../GLOSSARY.md#formatting-only) changes are collapsed into a "Formatting-only" group in each container by default.
- **R14** [Uncertain](../../GLOSSARY.md#uncertain) declarations are listed in an "Uncertain" group at the top level, each with its [candidates](../../GLOSSARY.md#candidate).
- **R15** A reported [file region](../../GLOSSARY.md#file-region) is shown as a "File region" row.
- **R16** [Unanalyzed](../../GLOSSARY.md#unanalyzed) source is listed in an "Unanalyzed" group.
- **R17** Selecting a row scrolls both sides to the declaration and highlights it.
- **R18** When the declaration is absent on one side, the outline says "Not present on the old side" or "Not present on the new side".
- **R19** Enter or a double-click moves the caret to the declaration on the new side, or the [old side](../../GLOSSARY.md#old-side) if it was removed.
- **R20** An edit to either side compares the file again, 300 ms after typing stops.
- **R21** A result for text that has changed since is never shown.
- **R22** The refresh action compares the file again.
- **R23** Labels are text, not color alone, and every row has an accessible name equal to its label.
- **R24** Closing the diff stops its comparison.

## Out of scope

- Three-way merges and diffs of directories or binary files.
- Diffs where the two sides are in different languages.

## References

- [ADR 0005](../../adr/0005-outline-attaches-to-the-native-diff-viewer.md)
- [Technical specification §9 and §10](../../TECHNICAL-SPECIFICATION.md#10-structural-outline)
