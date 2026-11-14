# Outline minimum width

Status: implemented, 2026-11-14

**Description:** The [structural outline](../../GLOSSARY.md#structural-outline) panel keeps a minimum width, so the splitter cannot squeeze the summary line and the rows into an unreadable column.

**Use case:** A reviewer drags the splitter to the right to give the editors more room but still wants the outline. The panel stops at a width where its summary and rows stay readable, instead of collapsing to a narrow strip. A later diff that remembered a narrower width opens at the minimum too.

## Requirements

- **R1** The outline panel is never narrower than 200 px, scaled with the IDE font size, while the diff window has room for it and the editors.
- **R2** The minimum applies in every outline state: a result, a fallback message and `Comparing…`.
- **R3** A diff whose remembered width is below the minimum opens the panel at the minimum width, and the editors take the rest of the width.
- **R4** The outline in a [three-sided diff](../../GLOSSARY.md#three-sided-diff) is at least as wide as the two-sided outline's minimum.

## Out of scope

- A minimum height for the panel.
- A setting for the minimum width.
- A maximum width for the panel or the editors.

## References

- [Details: design and tests](09-outline-minimum-width-details.md)
- [Remembered width](02-outline-toggle.md) (outline/02 R14)
- [Technical specification §10](../../TECHNICAL-SPECIFICATION.md#10-structural-outline)
- [Issue #157](https://github.com/agadek/smart-diff/issues/157)
