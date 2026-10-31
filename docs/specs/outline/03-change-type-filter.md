# Change type filter

Status: implemented, 2026-10-22

**Description:** The [change type filter](../../GLOSSARY.md#change-type-filter) in the outline toolbar hides chosen [change types](../../GLOSSARY.md#change-type) from the [structural outline](../../GLOSSARY.md#structural-outline).

**Use case:** A reviewer only cares about changed code. They hide moved and formatting-only changes, and the outline lists what is left.

## Requirements

- **R1** The filter can hide changed, added, removed, moved and formatting-only changes.
- **R2** Hiding changed also hides [signature changes](../../GLOSSARY.md#signature-changed).
- **R3** A change is hidden only when all of its labels are hidden.
- **R4** A hidden change with shown changes inside it stays as a container without labels.
- **R5** [Uncertain](../../GLOSSARY.md#uncertain), [rename candidate](../../GLOSSARY.md#rename-candidate) and [unanalyzed](../../GLOSSARY.md#unanalyzed) changes are never hidden.
- **R6** The [summary line](../../GLOSSARY.md#summary-line) still counts hidden changes, and ends with how many are hidden: `· 2 hidden`.
- **R7** When every change is hidden, the outline says "All changes are hidden by the filter".
- **R8** The filter is remembered and applies to every outline.
- **R9** Changing the filter updates the outline without comparing the file again.

## References

- [ADR 0003](../../adr/0003-conservative-classification.md)
- [Technical specification §10](../../TECHNICAL-SPECIFICATION.md#10-structural-outline)
