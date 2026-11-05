# Outline toggle

Status: implemented, 2026-11-05

**Description:** The [outline toggle](../../GLOSSARY.md#outline-toggle), a Structural Outline button in every diff toolbar, shows or hides the [structural outline](../../GLOSSARY.md#structural-outline). The panel's header has its own Hide and Minimise buttons, the toggle has a default keyboard shortcut, and the outline can wait as a thin strip at the right edge.

**Use case:** A reviewer wants the plain [native diff](../../GLOSSARY.md#native-diff) for a while. One click hides the outline, and another brings it back. When they need the width for the editors but expect to return to the outline soon, they minimise it to a strip and click the strip to restore it.

## Requirements

- **R1** The toggle is in the toolbar of every diff that can show the outline.
- **R2** Turning it off hides the outline and leaves the editors as they are.
- **R3** While the outline is hidden, no comparison runs.
- **R4** The toggle starts on when "Show structural outline automatically" is on, and off otherwise.
- **R5** The choice applies to the current diff window and to the next files it shows.
- **R6** Other diff windows keep their own choice.
- **R7** The outline panel's header has a Hide button with the platform's Hide icon that turns the outline toggle off for this diff window, as R2 to R6.
- **R8** The outline panel's header has a Minimise button that replaces the panel with a thin strip at the right edge of the diff.
- **R9** The strip shows the outline's [summary line](../../GLOSSARY.md#summary-line) as its tooltip and accessible name, and says that the outline is minimised before a result exists.
- **R10** Clicking the strip restores the panel at its previous width.
- **R11** While the outline is minimised it stays computed, so restoring it does not compare again.
- **R12** The outline toggle has a default keyboard shortcut that does not clash with the platform's diff shortcuts, and Keymap lists it.
- **R13** The outline toggle stays discoverable in a narrow diff window.
- **R14** The minimised state and the panel width are remembered for the next diff.

## References

- [Details: design and tests](02-outline-toggle-details.md)
- [Technical specification §10](../../TECHNICAL-SPECIFICATION.md#10-structural-outline)
- [Issue #115](https://github.com/agadek/smart-diff/issues/115)
