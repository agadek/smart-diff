# Settings page

Status: implemented, 2026-10-22; the outline view and relationship kind settings (R16 and R17) arrive with the [refactorings view](../relationships/06-refactorings-view.md), 2026-11-09.

**Description:** A settings page under Settings | Tools | SMART Diff holds the plugin's options, for all projects.

**Use case:** A developer prefers to open the [structural outline](../../GLOSSARY.md#structural-outline) only when they need it. They turn off "Show structural outline automatically" once, and every diff starts with the plain [native diff](../../GLOSSARY.md#native-diff).

## Requirements

- **R1** The settings apply to the whole IDE, not to one project.
- **R2** "Show structural outline automatically" is on by default.
- **R3** When it is off, every diff starts with the outline hidden, and the [outline toggle](../../GLOSSARY.md#outline-toggle) still shows it.
- **R4** "Show structural summary in the Changes tree" is on by default and is the tree's "Structural Summary" toggle.
- **R5** "Formatting-only changes" chooses between a collapsed group, the default, and inline rows.
- **R6** "Hide from the outline" has one checkbox per [change type](../../GLOSSARY.md#change-type), all off by default, and matches the outline's [change type filter](../../GLOSSARY.md#change-type-filter).
- **R7** "Default Comparison Engine" has one row per language with an engine, with a choice of the engines that support it.
- **R8** A language without a chosen engine uses the first registered one.
- **R9** The [analysis limits](../../GLOSSARY.md#analysis-limits) can be changed: largest file, most declarations per side and analysis time.
- **R10** "Show declarations in the Changes tree" is on by default and is the tree's "Declarations" toggle.
- **R11** "Hide from the Changes tree" has one checkbox per change type, all off by default, and matches the tree's own change type filter.
- **R12** The Changes tree's two toggles and its filter are remembered and are the same settings as in its View Options menu.
- **R13** "Follow the caret in the Changes tree" is on by default.
- **R14** Changing it does not clear summaries or rebuild the tree.
- **R15** When it is off, moving the caret does not change the Changes tree selection.
- **R16** "Outline view" chooses between the container tree, the default, and the refactorings view, and the outline toggle keeps the choice.
- **R17** "Hide from the refactorings view" has one checkbox per relationship kind, all off by default, and matches the refactorings view's menu.

## References

- [Hide and filter in the Changes tree](../changes-tree/04-hide-and-filter.md)
- [Refactorings view](../relationships/06-refactorings-view.md)
- [Technical specification §11](../../TECHNICAL-SPECIFICATION.md#11-settings)
