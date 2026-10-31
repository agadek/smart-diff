# Settings page

Status: implemented, 2026-10-22

**Description:** A settings page under Settings | Tools | SMART Diff holds the plugin's options, for all projects.

**Use case:** A developer prefers to open the [structural outline](../../GLOSSARY.md#structural-outline) only when they need it. They turn off "Show structural outline automatically" once, and every diff starts with the plain [native diff](../../GLOSSARY.md#native-diff).

## Requirements

- **R1** The settings apply to the whole IDE, not to one project.
- **R2** "Show structural outline automatically" is on by default.
- **R3** When it is off, every diff starts with the outline hidden, and the [outline toggle](../../GLOSSARY.md#outline-toggle) still shows it.
- **R4** "Show structural summary in the Changes tree" is on by default.
- **R5** "Formatting-only changes" chooses between a collapsed group, the default, and inline rows.
- **R6** "Hide from the outline" has one checkbox per [change type](../../GLOSSARY.md#change-type), all off by default, and matches the [change type filter](../../GLOSSARY.md#change-type-filter).
- **R7** "Default Comparison Engine" has one row per language with an engine, with a choice of the engines that support it.
- **R8** A language without a chosen engine uses the first registered one.
- **R9** The [analysis limits](../../GLOSSARY.md#analysis-limits) can be changed: largest file, most declarations per side and analysis time.

## References

- [Technical specification §11](../../TECHNICAL-SPECIFICATION.md#11-settings)
