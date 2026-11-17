---
status: accepted
---

# Review tree summaries attach through the tree's model property

[Summaries in the Git log and pull requests](../specs/changes-tree/07-review-tree-summaries.md) needs a hook into three
[review trees](../GLOSSARY.md#review-tree): the Git log's commit details, the changes of a GitHub pull request and the
changes of a GitLab merge request. None of them calls `ChangesViewModifier` ([ADR 0006](0006-changes-tree-summary-through-changes-view-modifier.md)),
and no extension point decorates their file nodes. Spike S11 ([technical specification §13](../TECHNICAL-SPECIFICATION.md#13-prototype-spikes))
read the platform and plugin classes of IntelliJ IDEA 2025.1.3 (the minimum target) and 2026.3 (the latest release
the build verifies against) on 2026-11-17 to choose a hook per tree.

## Findings

- **Every review tree is a `ChangesTree`.** The Git log's tree is the viewer of `VcsLogChangesBrowser`
  (`intellij.platform.vcs.log.impl`, an `AsyncChangesBrowserBase`). The pull and merge request trees are the
  `AsyncChangesTree` that `CodeReviewChangeListComponentFactory.createIn` (collaboration tools) builds for both
  plugins; its model is a `TreeModelBuilder` whose file nodes are an internal `ChangesBrowserNode<RefComparisonChange>`.
- **`ChangesTree.updateTreeModel` saves the tree state, calls `setModel`, then restores the state**, in that order
  on both 2025.1.3 and 2026.3 (bytecode of `ChangesTree.updateTreeModel(DefaultTreeModel, TreeStateStrategy)`).
  `JTree.setModel` fires the `model` property change synchronously, so a property change listener on the tree sees
  every new model on the EDT before the platform restores selection and expansion. Replacing file nodes there keeps
  the platform's own state handling, as `ChangesSummaryModifier` does for the Changes tree.
- **Finding the trees.** No listener announces a new review tree: the log builds its browser with the log tab, and
  the pull request tree is created when its details are shown inside the tool window's tab. An application-wide AWT
  listener for `HierarchyEvent.SHOWING_CHANGED` sees each `ChangesTree` when it is first shown, and
  `DISPLAYABILITY_CHANGED` when it is removed. Each adapter then decides whether the tree is its own:
  - Git log: the tree has an ancestor of class `com.intellij.vcs.log.ui.frame.VcsLogChangesBrowser`, checked by name.
    The class is final, internal to the log module, and its constructor changed between 2025.1 and 2026.3, so the
    plugin does not link against it.
  - GitHub: the tree's file nodes hold `RefComparisonChange` (collaboration tools, experimental) and the tree is under the
    `Pull Requests` tool window, or under a component of the GitHub plugin's packages.
  - GitLab: the same with the `Merge Requests` tool window and the GitLab plugin's packages.
- **Revisions.** A Git log node holds the `Change` the log's own diff opens: the parent against the commit, the
  range's change for several commits, and one `Change` per parent under "Show changes to parents"; a `MergedChange` is
  a `Change`. A pull request node's `RefComparisonChange` holds the base and head revision numbers and paths its diff
  compares; the class is experimental in both builds. The adapter turns it into a `Change` whose revisions call the public `GitContentRevision.createRevision`
  only when the summary executor reads them, so nothing is read on the EDT. A revision whose commit is not fetched
  fails to read and the row stays plain.
- **Opening a row.** A Git log node's `Change` opens through the same platform diff action as the Changes tree, with
  the `OutlinePath` in the change context. The pull request trees open their diff through the review's view model,
  which takes no context: the adapter selects the file row and calls the tree's own Enter handler (set by
  `createIn`), after recording the `OutlinePath` for the file's path; the next outline shown for that file within 5 s
  takes it, matched by its path, or by its name when the diff content has no path. When the tree has no handler, the file's own diff opens without a selection (R11).
- **Renderer wrapping** was not needed, and no tree falls back to the context menu action of R20.
- **Modal commit dialog:** its tree is a `ChangesListView` that the Changes view modifier does not reach; it is left
  out of scope as the feature proposed.

## Decision

All three review trees use **model post-processing through the tree's `model` property**:

- `ReviewTreeWatcher` (application level, registered by `smartdiff-vcs.xml`) installs one AWT hierarchy listener and
  asks each `ReviewTreeAdapter` extension whether a newly shown `ChangesTree` is its tree.
- One adapter per tree, each in its own optional descriptor so the plugin loads without it (R21):
  - `GitLogTreeAdapter` in `smartdiff-vcs-log.xml`, loaded with `Git4Idea` (the spec proposed
    `com.intellij.modules.vcs`; the log adapter only matches trees by class name, and the "Git log" of the feature is
    shown with Git, so its own plugin id keeps the descriptor distinct from `smartdiff-vcs.xml`).
  - `GitHubTreeAdapter` in `smartdiff-github.xml`, loaded with `org.jetbrains.plugins.github`.
  - `GitLabTreeAdapter` in `smartdiff-gitlab.xml`, loaded with `org.jetbrains.plugins.gitlab`.
- An attached tree gets a `SummaryScope` of `ChangesSummaryService` and a property change listener; on each new model
  the adapter's file nodes are replaced by `ReviewSummaryNode`s with `DeclarationNode` helper children, before the
  platform restores the tree state. Arriving summaries and settings changes ask the tree to rebuild
  (`ChangesTree.rebuildTree`), which runs the same step.

The linked API is public except one class: `ChangesTree`, `ChangesBrowserNode`, `GitContentRevision` and Swing are
public, while `RefComparisonChange` is experimental (Plugin Verifier reports it and its four getters). It has no public
replacement, since the collaboration tools give a request's changes only as this class, so it is used only by
`CodeReviewTreeAdapter`, the one adapter class behind both request trees, and listed in
[docs/PLATFORM-API.md](../PLATFORM-API.md). The whole review tree hook runs through `ApiGuard` as the
`REVIEW_TREE_HOOK` feature: a missing or changed class turns the review tree summaries off for the session and leaves
the trees as the platform draws them (platform/01 R10, R13, R14). The two class names (`VcsLogChangesBrowser`, the
plugins' packages) are matched as strings, so a rename only stops the match: the tree is then drawn as the platform
draws it. An adapter that throws is logged once and stops adding summaries to that tree.

## Consequences

- The build compiles against `Git4Idea`, `org.jetbrains.plugins.github`, `org.jetbrains.plugins.gitlab` and the
  `intellij.platform.collaborationTools` module. Plugin Verifier resolves them on IntelliJ IDEA, PyCharm and RustRover.
- The proof is `ReviewTreeSummaryTest`, which drives a real `ChangesTree` through `updateTreeModel` and checks that
  the summary rows are in place and the restored selection still holds. The trees of the Git log and the pull request
  tool windows are checked manually on 2025.1 and the latest IDE ([manual checks](../specs/changes-tree/07-review-tree-summaries-details.md#tests)).
- A platform change that moves `setModel` after the state restore would collapse expanded declaration rows on
  rebuild but still show the summaries.
