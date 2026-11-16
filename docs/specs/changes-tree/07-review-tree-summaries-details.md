# Summaries in the Git log and pull requests: details

Status: proposed, 2026-11-16

**Feature:** [Summaries in the Git log and pull requests](07-review-tree-summaries.md)

## Design

### Current behaviour

Summaries reach only the Local Changes view and the Commit tool window, through the public `changesViewModifier` extension point ([ADR 0006](../../adr/0006-changes-tree-summary-through-changes-view-modifier.md)). The [review trees](../../GLOSSARY.md#review-tree) build their models without it. A reading of the platform sources (branch 251 and master, 2026-11-16) shows:

| Review tree | Built by | File node user object | Context menu group |
| --- | --- | --- | --- |
| Git log commit details | `VcsLogChangesBrowser`, an `AsyncChangesBrowserBase` whose model builds a `TreeModelBuilder` directly | `Change` (a `MergedChange` for changes to several parents) | `Vcs.Log.ChangesBrowser.Popup`, toolbar `Vcs.Log.ChangesBrowser.Toolbar` |
| GitHub pull request changes | `CodeReviewChangeListComponentFactory` (collaboration tools), an `AsyncChangesTree` with its own `TreeModelBuilder` | `RefComparisonChange` | `Github.PullRequest.Changes.Popup` |
| GitLab merge request changes | the same factory | `RefComparisonChange` | `GitLab.Merge.Request.Changes.Popup` |

Neither builder calls `ChangesViewModifier`, and no extension point decorates their file nodes. A diff opened from any of them is a native two-sided diff, so the outline already attaches there through `OutlineDiffExtension`; only the tree has no summary.

### Spike S11

Spike S11 ([technical specification §13](../../TECHNICAL-SPECIFICATION.md#13-prototype-spikes)) decides, for each review tree on 2025.1 and the latest IDE:

1. How the plugin learns that a review tree was created or rebuilt, and gets its `ChangesTree`.
2. Whether it can replace file nodes and add helper child nodes after the platform built the model, as `ChangesSummaryModifier` does, or only change how file rows are rendered.
3. How a file node gives the two revisions of R4: the `Change` of a log node, and for a `RefComparisonChange` its before and after revision numbers and paths.
4. How a row opens the file's diff with an `OutlinePath` (R10).

Candidates, in order of preference:

- **Model post-processing:** after each rebuild, replace file nodes with `SummaryChangeNode` and add `DeclarationNode` children, through a tree model listener on the tree found in step 1. Gives R1 to R14 like the Changes tree.
- **Renderer wrapper:** wrap the tree's cell renderer to append the summary text. Gives the summary line only; declaration rows then come from R20.
- **Context menu action (R20):** a "Structural Summary" action added to the tree's context menu group, reading the selected changes from the tree's data context, which shows a popup with the outline's rows. Public action groups only; always possible.

Public API is preferred. An experimental or internal API is accepted only when Plugin Verifier's report lists it, the use sits behind one adapter class per tree, and a failure falls back to the next candidate. The result and the chosen hook per tree are recorded in ADR 0012 and in §13 before any code depends on them. A tree for which only the context menu action works gets R20, and R1 to R3 for it are then marked *(not applicable, S11)* in the feature file.

### Adapters

One `ReviewTreeAdapter` per review tree, each in its own optional descriptor so R21 holds:

| Adapter | Descriptor | Depends on |
| --- | --- | --- |
| `GitLogTreeAdapter` | `smartdiff-vcs-log.xml` | `com.intellij.modules.vcs` (the log is a platform module; the spike confirms which module id loads it) |
| `GitHubTreeAdapter` | `smartdiff-github.xml` | `org.jetbrains.plugins.github` |
| `GitLabTreeAdapter` | `smartdiff-gitlab.xml` | `org.jetbrains.plugins.gitlab` |

An adapter finds its trees, lists their file nodes with a `Change` for each (converting a `RefComparisonChange` with the revisions of step 3), applies summaries to the model, rebuilds the tree and opens a row. The build adds the GitHub and GitLab plugins as bundled plugins for compilation, and Plugin Verifier resolves them on both target IDEs.

### Revisions

- **One commit:** the log's `Change`, its parent against the commit.
- **Several commits:** the change the browser shows for the range, from the oldest parent to the newest commit.
- **A merge commit with "Show changes to parents":** each parent's node has its own `Change`, summarized on its own; a `MergedChange` node is summarized from the change it shows.
- **A pull request:** the base and head revisions of the `RefComparisonChange`, the same pair its diff compares (R4). When the pull request's commits are not fetched yet, reading a revision fails and the row stays plain (R9) until the plugin fetches them and the tree rebuilds.

Content is read with `ContentRevision.getContent` on the summary executor (R16). Revision numbers of committed revisions do not change, so they are the version part of the cache key.

### Summary, rows and filter

The summary text and rows come from the same `OutlineModel.summary` and `OutlineModel.build` as the Changes tree, so R5 to R8 follow from them, and `ChangesTreeFilter` applies the Changes tree's filter (R13). The review trees have no View Options toggles of their own: the two Changes tree settings and the filter apply to every tree (R12), and a change to either rebuilds the review trees as it rebuilds the Changes tree, through `ChangesSummarySettingsListener`. Declaration rows are helper nodes (`markAsHelperNode`), so they are not part of the selected changes (R14).

### Cache, scope and threading

`ChangesSummaryService` today forgets every file that is not in the last `summaries()` call, so a second tree would evict the Changes tree's summaries. It gains a `SummaryScope`: one for the Changes tree and one per review tree instance, each with its own cache and its own retained set (R19). Each scope:

- keeps the entries of its current files, plus at most 2000 more in least-recently-used order, so a commit selected again is still cached (R18); 2000 is four full 500-file trees, at about 1 KB of rows per file;
- has a generation that increases when the tree's files change; a queued task whose generation is old is skipped before it reads any content (R17);
- rebuilds its own tree when summaries arrive, at most once per 300 ms, as the Changes tree does.

All scopes share the one-thread summary executor and the 500-file limit applies per scope (R15). A scope is disposed with its tree.

### Opening a row

- **Git log:** the adapter takes the producer from `VcsLogChangesBrowser.getDiffRequestProducer`, or builds a `ChangeDiffRequestProducer` with the `OutlinePath` in its context, and shows it through `DiffManager`, with the tree's other changes next to it.
- **Pull requests:** the diff opens through the pull request's own view model, which takes no context. Unless S11 finds a way to pass one, the adapter records the `OutlinePath` with the file's path in a project service and asks the tree to open the file's diff; the next outline shown for that path within 5 s takes the path and selects the row. 5 s covers loading both revisions of a large file. Otherwise the row opens the diff without a selection (R11).

### Edge cases

- **The same file twice in one tree**, under two parents of a merge: two nodes, two `Change`s, two keys.
- **Renamed or moved file:** summarized as a modified file, as in the Changes tree.
- **A tree disposed while summaries are pending:** its scope is disposed and its queued tasks are skipped.
- **Several Git log tabs or pull request tabs:** each tree has its own scope.
- **A pull request larger than 500 files:** the first 500 file nodes in tree order, as [changes-tree/03](03-coverage-and-scale.md).
- **A hook that throws or no longer finds its tree** after an IDE update: the adapter logs once and leaves that tree as the platform draws it.

## Tests

| Test | Checks |
| --- | --- |
| `ReviewTreeSummaryTest` (platform test with a `TreeModelBuilder` model of changes with fixed revisions, through each adapter's apply step) | R1 to R3, R5 to R9, R13, R14, R15 |
| `ReviewTreeRevisionsTest` (a temporary Git repository with one commit, a range and a merge commit) | R4, R7 |
| `SummaryScopeTest` | R16 to R19 |
| `ReviewTreeOpenTest` | R10, R11 |
| `ChangesSummarySettingsListenerTest` (extended) | R12 |
| `ReviewTreeActionTest`, only for a tree that S11 gives R20 | R20 |
| `OptionalDescriptorTest` (extended) and Plugin Verifier on both target IDEs | R21 |

Manual checks, one row per review tree on 2025.1 and the latest IDE, use a commit and a pull request or merge request that change a Java and a Kotlin file: the summary, expanding the rows, opening a row and the filter.

## Open questions

- **Modal commit dialog:** include it once S11 shows whether its tree takes the same hook as the log, or keep it out of scope.
- **View Options in review trees:** keep the Changes tree's toggles as the only control (the proposal), or add the toggles to each review tree's toolbar.
