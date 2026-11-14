# Wheel steps through rows

Status: accepted, 2026-11-14

**Description:** While a row of the [structural outline](../../GLOSSARY.md#structural-outline) is selected, the mouse
wheel over the outline selects the next or previous change row, so a reviewer can walk through the changes with the
wheel alone.

**Use case:** A reviewer has picked a change in the outline and wants to read the next one. They roll the wheel over the
outline, and each notch moves to the next change and shows it in the editors, without reaching for the keyboard or
aiming at the next row.

## Requirements

- **R1** While a row is selected and the pointer is over the outline's tree, each wheel notch down selects the next row
  and each notch up selects the previous row.
- **R2** The row selected by the wheel is shown in the editors and in the outline exactly as a row selected with the
  Down or Up arrow key is.
- **R3** The wheel skips rows that frame nothing in the editors: section headings and the formatting-only, uncertain and
  unanalyzed group rows.
- **R4** At the first or last such row, a further notch in that direction leaves the selection as it is.
- **R5** With no row selected, the wheel scrolls the outline as before.
- **R6** A trackpad or high-resolution wheel moves one row per full notch of scrolling, never one row per small event.
- **R7** A horizontal scroll, including Shift with the wheel, scrolls the outline horizontally and never changes the
  selection.
- **R8** Stepping with the wheel does not move keyboard focus.

## Out of scope

- The wheel over the diff editors, which scrolls them as before.
- The Changes tool window tree and the merge window's outline.
- A setting to turn stepping off.

## References

- [Details: design and tests](08-wheel-steps-through-rows-details.md)
- [Structural outline](01-structural-outline.md)
