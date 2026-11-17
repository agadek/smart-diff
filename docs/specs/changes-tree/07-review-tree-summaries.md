# Summaries in the Git log and pull requests

Status: implemented, 2026-11-17

**Description:** Each changed file in a [review tree](../../GLOSSARY.md#review-tree), the Git log's commit details and the changes of a GitHub pull request or GitLab merge request, gets the same [summary line](../../GLOSSARY.md#summary-line) and [declaration](../../GLOSSARY.md#declaration) rows as in the [Changes tree](../../GLOSSARY.md#changes-tree).

**Use case:** A reviewer opens a pull request or a commit in the IDE and scans its changed files. They see which declarations each file changes, and open the one they want, before reading any diff, as they already can for their own local changes.

## Requirements

- **R1** In the Git log's commit details, a changed file with a [supported language](../../GLOSSARY.md#supported-language), a [comparison engine](../../GLOSSARY.md#comparison-engine) and at least one reported change gets a summary line after its name.
- **R2** In the changes of a GitHub pull request, such a file gets a summary line after its name.
- **R3** In the changes of a GitLab merge request, such a file gets a summary line after its name.
- **R4** The summary line compares the same two revisions as the file's diff opened from that review tree.
- **R5** The summary line has the same text, counts and hidden counts as the Changes tree's for the same two revisions.
- **R6** A summarized file can be expanded to show its declaration rows, with the same nesting, groups and [labels](../../GLOSSARY.md#label) as the file's [structural outline](../../GLOSSARY.md#structural-outline).
- **R7** Added and deleted files get a summary line and declaration rows as [changes-tree/06](06-added-deleted-file-summaries.md) specifies.
- **R8** A supported file over an [analysis limit](../../GLOSSARY.md#analysis-limits) shows its reason in place of the summary and has no declaration rows.
- **R9** A binary or unsupported file, a file with no engine, and a file whose revision cannot be read keep the platform's row.
- **R10** Opening a declaration row opens the file's diff from that review tree, with that declaration selected in the outline.
- **R11** When the diff cannot open with the declaration selected, the row opens the file's diff without a declaration selected.
- **R12** The "Show structural summary in the Changes tree" and "Show declarations in the Changes tree" settings also apply to review trees.
- **R13** The Changes tree's [change type filter](../../GLOSSARY.md#change-type-filter) also applies to review trees, including the hidden counts.
- **R14** Declaration rows are not changes: the review tree's selected changes, file count, diff preview and actions are the same as without them.
- **R15** At most 500 file nodes of one review tree are summarized, the first 500 in the tree's depth-first order.
- **R16** Summaries are computed in the background, one file at a time, and no revision content is read on the UI thread.
- **R17** When the selected commits or the shown pull request change, summaries still pending for files no longer in the tree are not computed.
- **R18** Selecting a commit again shows its summaries within 1 s without comparing again, while the cache holds them.
- **R19** Summaries in one review tree never remove or change the summaries of the Changes tree or of another review tree.
- **R20** A review tree that spike S11 records as having no supported hook has a "Structural Summary" action in its context menu instead, which shows the selected files with their summary lines and declaration rows in a popup. *(not applicable, S11: every review tree takes the model hook)*
- **R21** The plugin loads, and the other trees are unchanged, when Git, GitHub or GitLab support is not installed or is disabled.

## Out of scope

- The modal commit dialog, whose tree has no hook either; spike S11 left it out (ADR 0013).
- The file history panel and change browsers of other plugins.
- Caret follow and [reviewed marks](../../GLOSSARY.md#reviewed-mark) in review trees.
- Posting summaries to GitHub or GitLab, or a review UI of the plugin's own.
- Relationships across files or across commits.
- A setting for the 500-file limit, or separate settings for review trees.

## References

- [Details: design and tests](07-review-tree-summaries-details.md)
- [Changes tree summary](01-changes-tree-summary.md)
- [Declarations in the Changes tree](02-changes-tree-declarations.md)
- [Changes tree coverage and scale](03-coverage-and-scale.md)
- [Hide and filter in the Changes tree](04-hide-and-filter.md)
- [Summaries for added and deleted files](06-added-deleted-file-summaries.md)
- [Analysis fallback](../outline/05-analysis-fallback.md)
- [ADR 0006](../../adr/0006-changes-tree-summary-through-changes-view-modifier.md)
- [ADR 0013](../../adr/0013-review-tree-summaries-through-the-tree-model-property.md)
- [Technical specification §9.4](../../TECHNICAL-SPECIFICATION.md#94-changes-tree-summary) and [§13, spike S11](../../TECHNICAL-SPECIFICATION.md#13-prototype-spikes)
- [Roadmap: summary in pull request reviews and the Git log](../../ROADMAP.md#summary-in-pull-request-reviews-and-the-git-log)
