# Settings page

Status: implemented, 2026-10-22; the outline view and relationship kind settings (R16 and R17) arrive with the [refactorings view](../relationships/06-refactorings-view.md), 2026-11-09; the structure-view fallback setting (R18) arrives with the [structure-view fallback](../languages/06-structure-view-fallback.md), 2026-11-13; the comment-only setting (R19) is implemented with [comment-only changes](../outline/11-comment-only-changes.md), 2026-11-16; the moved-code marker setting (R20) is implemented with [moved-code markers](../outline/13-moved-code-markers.md), 2026-11-16; the rewrite-only setting (R22) arrives with [opt-in equivalence rules](../declarations/08-equivalence-rules.md), proposed 2026-11-17.

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
- **R18** "Use the IDE's structure view for other languages" is on by default.
- **R19** "Mark comment-only changes" is off by default, is the same setting as the outline filter menu's checkbox, and adds a comment-only checkbox to "Hide from the outline" while it is on.
- **R20** "Mark moved code in the editors" is on by default.
- **R21** "Show moved lines in files without an engine" is on by default.
- **R22** "Mark rewrite-only changes" is off by default, is the same setting as the outline filter menu's checkbox, lists each language's equivalence rules with one checkbox each, all on by default and enabled only while it is on, and adds a rewrite-only checkbox to "Hide from the outline" while it is on.

## References

- [Hide and filter in the Changes tree](../changes-tree/04-hide-and-filter.md)
- [Refactorings view](../relationships/06-refactorings-view.md)
- [Structure-view fallback](../languages/06-structure-view-fallback.md)
- [Comment-only changes](../outline/11-comment-only-changes.md) (R19)
- [Opt-in equivalence rules](../declarations/08-equivalence-rules.md) (R22)
- [Moved-code markers](../outline/13-moved-code-markers.md) (R20)
- [Moved lines in unsupported files](../engines/04-moved-lines.md) (R21)
- [Technical specification §11](../../TECHNICAL-SPECIFICATION.md#11-settings)
