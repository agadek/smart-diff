# Outline settings button

Status: proposed, 2026-11-14

**Description:** A settings button in the [structural outline](../../GLOSSARY.md#structural-outline)'s toolbar opens *Settings | Tools | SMART Diff*, and the outline that is open shows the new settings as soon as they are applied.

**Use case:** A reviewer in a diff wants formatting-only changes shown inline, or a larger analysis limit for a big file. They click the gear in the outline, change the option and press OK. The outline of the diff they are in shows the change at once, without closing the diff or looking for the page in the IDE's settings.

## Requirements

- **R1** The outline toolbar has a Settings button, with a gear icon and the tooltip `SMART Diff Settings`, right after the refresh action.
- **R2** Clicking the button opens the IDE settings on the *Tools | SMART Diff* page, for the diff's project.
- **R3** The outline of a [three-sided diff](../../GLOSSARY.md#three-sided-diff) and of the merge window has the same button.
- **R4** The button is shown and enabled in every outline state: a result, a fallback message, `Comparing…` and a language without an engine.
- **R5** After the settings are applied, every open outline shows the changed display settings without being reopened: hidden change types, formatting-only changes, the outline view and hidden relationship kinds.
- **R6** After the settings are applied, an open outline compares the two sides again only when the engine for its language, the [analysis limits](../../GLOSSARY.md#analysis-limits) or the structure-view fallback setting changed.
- **R7** An outline that shows changed display settings keeps its selection when the selected row is still shown.
- **R8** Turning off "Show structural outline automatically" does not hide an outline that is already open.
- **R9** Closing the settings with Cancel leaves the outline, its selection and the editors as they were.

## Out of scope

- A settings button in the Changes tree or on the minimised outline strip.
- A keyboard shortcut for the button.
- Editing settings in a popup inside the outline.
- New settings.

## References

- [Details: design and tests](10-outline-settings-button-details.md)
- [Settings page](../settings/01-settings-page.md)
- [Technical specification §10](../../TECHNICAL-SPECIFICATION.md#10-structural-outline) and [§11](../../TECHNICAL-SPECIFICATION.md#11-settings)
- [Roadmap: Settings button in the outline](../../ROADMAP.md#settings-button-in-the-outline)
