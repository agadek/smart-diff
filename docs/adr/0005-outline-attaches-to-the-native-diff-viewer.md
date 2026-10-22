---
status: accepted
---

# The outline attaches to the native diff viewer through a DiffExtension

The structural outline is added beside the editors of IntelliJ's own side-by-side viewer by a `DiffExtension` (Option A in [technical specification §9.1](../TECHNICAL-SPECIFICATION.md#9-integration-and-threading)), rather than shown in an alternate viewer registered as a `DiffTool` (Option B). Spike S1 attached a placeholder both ways on IntelliJ IDEA Community 2025.1.3, in platform tests that open diffs through the IDE's own `DiffRequestProcessor` (`S1DiffIntegrationSpikeTest`). Option A attached to every in-scope diff: local changes (VCS revision against the editable local file), VCS history (two revisions) and "Compare with…" between files, in Java and in Kotlin. Each time the extension received a `SimpleDiffViewer`, a `TwosideTextDiffViewer`, with both editors showing the request's texts. Option B also worked, but it hides the native viewer from every other `DiffExtension` and turns the outline switch into a global viewer setting.

## How Option A attaches

- `DiffExtension.onViewerCreated(viewer, context, request)` runs on the EDT for every viewer the `DiffRequestProcessor` creates. That processor runs diff windows, diff editor tabs and embedded diff panels; the combined "all files in one view" diff calls the same extension for each file block.
- The viewer's component is a `DiffPanelBase` (public API in `diff-impl`) with a `BorderLayout` whose centre holds the editors. The outline moves that centre component into a `OnePixelSplitter` with the outline as the second component. The extension runs before the viewer is initialised, so the editors and their listeners are untouched.
- Hiding the outline sets the splitter's second component to null and leaves both editors in place; showing it restores it. The spike checked both.
- The toolbar button that switches between the outline and the plain native diff (§10) is an action in the `Diff.ViewerToolbar` group (`IdeActions.DIFF_VIEWER_TOOLBAR`), which every native viewer adds to its toolbar. The action finds the viewer through `DiffDataKeys.DIFF_VIEWER` and changes only that diff.

## Considered Options

- **Option A, `DiffExtension` panel (chosen):** the native viewer stays the viewer, so its editors, highlighting, actions and other plugins' extensions (for example blame or line status) keep working, and the switch is per diff. It relies on the viewer's component being a `DiffPanelBase` with editors in the centre, which is a layout detail rather than an API contract.
- **Option B, alternate `FrameDiffTool`:** fully under our control, but the `DiffRequestProcessor` passes our wrapper to every `DiffExtension`, so extensions that look for `TwosideTextDiffViewer` (ours included) no longer see the native viewer. Choosing a tool reorders the global diff tool list in `DiffSettings`, so switching between the outline and the native viewer would change every later diff instead of only the current one.

## Consequences

- Attachment is defensive: if the viewer is not a `TwosideTextDiffViewer`, its component is not a `DiffPanelBase`, or the centre component is missing, the extension does nothing and the native diff shows as usual. An integration test in the Plugin Verifier's IDE range catches a layout change that Plugin Verifier itself cannot see.
- The unified (one-sided) viewer and three-sided viewers get no outline in the first release. Their components are also `DiffPanelBase`s, so the same attachment can serve them later.
- In the combined diff the outline appears inside each file block. Whether that is usable is checked during validation (milestone 7); the extension can skip combined-diff blocks if it is not.
- The local side of a local-change diff is editable (the spike saw `FileDocumentContent` with a writable document), so the lifecycle in §9.2 must rerun the comparison on document changes, as specified.

## Addendum (2026-10-21): the SMART Diff viewer

To fade the native marks of changes the outline filter hides, SMART Diff also registers its own side-by-side viewer, a subclass of the native one, in the diff's viewer list. It is offered beside the native viewer, not in its place, so this decision stands: the outline still attaches to whichever side-by-side viewer is shown, and the native viewer stays the default ([technical specification §10](../TECHNICAL-SPECIFICATION.md#10-structural-outline)).
