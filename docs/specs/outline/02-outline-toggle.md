# Outline toggle

Status: implemented, 2026-10-21

**Description:** The [outline toggle](../../GLOSSARY.md#outline-toggle), a Structural Outline button in every diff toolbar, shows or hides the [structural outline](../../GLOSSARY.md#structural-outline).

**Use case:** A reviewer wants the plain [native diff](../../GLOSSARY.md#native-diff) for a while. One click hides the outline, and another brings it back.

## Requirements

- **R1** The toggle is in the toolbar of every diff that can show the outline.
- **R2** Turning it off hides the outline and leaves the editors as they are.
- **R3** While the outline is hidden, no comparison runs.
- **R4** The toggle starts on when "Show structural outline automatically" is on, and off otherwise.
- **R5** The choice applies to the current diff window and to the next files it shows.
- **R6** Other diff windows keep their own choice.

## References

- [Technical specification §10](../../TECHNICAL-SPECIFICATION.md#10-structural-outline)
