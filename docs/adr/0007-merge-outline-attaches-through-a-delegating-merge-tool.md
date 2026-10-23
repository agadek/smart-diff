---
status: accepted
---

# The merge outline attaches to the merge window through a delegating MergeTool

The three-way merge outline ([THREE-WAY-MERGE.md](../THREE-WAY-MERGE.md)) attaches to three-sided diffs through the existing `DiffExtension` (plan §6.1) and to the merge window through a `MergeTool` registered first, which lets the platform's text merge tool create the viewer and then puts the outline beside that viewer's editors (plan §6.2). Spike S6 read the platform sources for 2025.1 (branch 251) and checked the attachment in a platform test, `S6MergeSpikeTest`, with a placeholder behind the registry key `smartdiff.merge.outline.preview` (off by default).

## What the platform offers

- **The merge window runs no `DiffExtension`.** `DiffExtension.onViewerCreated` is called by `DiffRequestProcessor` and the combined diff only. The merge window is run by `MergeRequestProcessor`, which asks the first tool in `DiffManagerEx.getMergeTools()` whose `canShow` accepts the request to create a `MergeTool.MergeViewer`. The text merge viewer builds its inner three-sided viewer itself, so no extension ever sees it. The test confirms this: opening a merge creates no viewer that an extension is offered.
- **Merge tools.** `getMergeTools()` returns the `diff.merge.MergeTool` extensions in their order, then the platform's `TextMergeTool` and `BinaryMergeTool`. `MergeTool`, `MergeContext`, `TextMergeRequest` and `DiffManagerEx` are public API; `TextMergeTool`, `TextMergeViewer`, `MergeThreesideViewer`, `TextMergeChange` and `MergeRequestProcessor` are marked `@ApiStatus.Internal`.
- **Code that looks for the viewer by type.** The window publishes its viewer as `DiffDataKeys.MERGE_VIEWER`, and two platform actions cast it to `TextMergeViewer`: Annotate in the merge window (`AnnotateDiffViewerAction`) and the toggle for automatic import resolution (`MergeImportUtil`). A wrapper viewer would switch both off.
- **The viewer's component** is the inner viewer's `DiffPanelBase` with the editors in the `BorderLayout` centre, as for two-sided viewers (ADR 0005), and it is built when the viewer is created, before `init()`.
- **The toolbar.** The merge viewer's toolbar does not include `Diff.ViewerToolbar`; it ends with the action group `Diff.Conflicts.Additional.Actions` (declared in `PlatformActions.xml`, empty by default), where the outline toggle can go.

## Decision

`MergeOutlineTool` is registered with `order="first"`. Its `canShow` accepts a `TextMergeRequest` whose three contents are documents of one language with an engine, when another tool also accepts it. Its `createComponent` finds that other tool, the first one after itself in `getMergeTools()` that accepts the request (normally the platform's text merge tool, found through the list, never by its class), lets it create the viewer, inserts the outline splitter into the viewer's component, and returns **the delegate's viewer object itself**. The outline's lifetime can hang on that viewer, which is a `Disposable`.

## Considered Options

- **Option A, `DiffExtension` (rejected):** the merge window never calls one (above).
- **Option B as planned, a wrapper `MergeViewer` (rejected):** it would hide the `TextMergeViewer` from Annotate and the import-resolution toggle, and it would have to forward the toolbar, resolve actions, close handler and focus for nothing the decorating variant needs.
- **Option B as decided, decorate the delegate's viewer in place (chosen):** public API only, the platform's viewer stays the viewer, and the attachment is the one ADR 0005 already relies on. It relies on the same layout detail: if the component is not a `DiffPanelBase` with editors in the centre, nothing is attached and the native merge window shows as usual.
- **Find the merge window's component from outside (rejected):** more layout dependence and no lifecycle, as the plan says.

## Consequences

- Three-sided diffs keep using `OutlineDiffExtension`, which gains a branch for `ThreesideTextDiffViewer` with three documents of one language. Its own base class, `ThreesideDiffViewer`, is internal API, so the viewer is only checked by type and used through the public `FrameDiffTool.DiffViewer` interface.
- The outline toggle in the merge window goes into `Diff.Conflicts.Additional.Actions` (phase 3).
- Framing a declaration in the merge window's three editors needs the editors, which the public API does not expose on a `MergeViewer`; phase 3 finds them in the viewer's component or through the `EditorFactory`, by the request's documents.
- Another plugin's merge tool registered before SMART Diff's still wins; the outline then does not appear in that window.
