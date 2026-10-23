---
status: accepted
---

# The Changes tree summary attaches through the changesViewModifier extension point

Each changed file's structural summary, such as `2 changed · 1 added`, is added to the Local Changes and Commit tree through the platform's `com.intellij.vcs.changes.changesViewModifier` extension point ([technical specification §9.4](../TECHNICAL-SPECIFICATION.md#94-changes-tree-summary)). Spike S5 read the platform sources for 2025.1 (branch 251) and the current master to find which hooks let a plugin add text or child nodes to file entries of that tree.

## What the platform offers

- **`ChangesViewModifier` (public, dynamic, project-level):** the Local Changes view calls `modifyTreeModelBuilder(builder)` for every extension after it has inserted the change lists and before it builds the tree, on a background thread. The builder is the platform's `TreeModelBuilder`, whose root (`myRoot`) is public API. A modifier can therefore insert nodes anywhere in the tree, including under or in place of a file node. Publishing `ChangesViewModifier.TOPIC` rebuilds the tree. On master the call moved to `ChangesViewUtil.createTreeModel`, with the same contract.
- **`ChangeNodeDecorator`:** decorates file rows with text before and after the name, but the Local Changes view passes only its own decorators (remote status and partial commit); no extension point adds one.
- **`ChangesFileNameDecorator`:** a project service that renders file names, marked `@ApiStatus.Internal`.
- **`ChangesBrowserChangeNode`:** the file node, a public class with a protected constructor, so a plugin can create its own file nodes for a change. Its decorator and extra text are private and fixed at construction.

## Decision

The modifier replaces each file node that has a summary with a `SummaryChangeNode`, a subclass of `ChangesBrowserChangeNode` for the same `Change`. It renders by asking the original node to render itself, which keeps the platform's decorations such as partial-commit marks, and then appends the summary in grey. Selection, inclusion checkboxes, speed search and actions work on the `Change`, so they are unchanged. Level 2 can add child nodes under the same node.

## Considered Options

- **Replace the file node, render through the original (chosen):** public API only, keeps every decoration, and the summary sits on the file's own row as the roadmap describes.
- **A child row under each file:** public API only, but every file becomes expandable to show one line, and level 2 would need a second kind of child.
- **Wrap the tree's cell renderer:** needs the view's tree component, which is reachable only through internal classes, and would also change rows of other plugins' trees.
- **Replace `ChangesFileNameDecorator`:** internal API, and only one plugin can replace a service.

## Consequences

- Only the Local Changes view and the Commit tool window, which share that tree, show summaries. The modal commit dialog, the Git log's commit details and other change browsers build their trees without modifiers.
- Summaries need the old content of each file, which the VCS may have to load, so they are computed in the background and the tree is rebuilt when they arrive. A file's row first shows without a summary.
- The replacement relies on the builder being a `TreeModelBuilder`; with any other builder the modifier does nothing.
- Level 2 adds the file's declarations as helper child nodes of the same node, so they stay out of the selected and included changes, and opens them through the `changesViewNodeAction` extension point, which lets a plugin handle double-click and Enter on a node of this tree. That extension point is marked experimental. The Commit tool window draws an inactive checkbox on each declaration row, since only the tree itself decides which rows have one.
