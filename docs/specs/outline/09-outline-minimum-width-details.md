# Outline minimum width: details

Status: implemented, 2026-11-14

Feature: [Outline minimum width](09-outline-minimum-width.md)

## Design

### Why the splitter did not keep a usable width

`insertOutlineSplitter` puts the editors and the outline panel in a `OnePixelSplitter`. The platform's `Splitter` honors each component's `minimumSize` (its `myHonorMinimumSize` field defaults to `true`), so the panel cannot be dragged below the width that the panel reports. Until now the panel reported only the minimum that its `BorderLayout` computes from the header and the scroll pane, which is about 133 px at the default font size.

Setting a minimum size on the tree does not change that: the tree is the view of a `JScrollPane`, and the scroll pane's viewport minimum does not forward the view's minimum. The minimum that the splitter reads stays the panel's small computed one, so the tree can still be dragged below any minimum set on it. The narrow width is also saved in `smartdiff.outline.proportion` and reused by the next diff (outline/02 R14).

### Minimum width

`OutlinePanel.getMinimumSize()` returns the larger of the computed minimum and `outlineMinimumWidth()`:

- `outlineMinimumWidth()` is defined next to `insertOutlineSplitter` in `OutlineSplitter.kt` and returns `JBUI.scale(MINIMUM_WIDTH)` with `MINIMUM_WIDTH = 200`, so the limit follows the IDE's font size and display scaling.
- The computed minimum stays the source of the height and of any width larger than 200 px, such as a wider header at a large font size. The method does not replace the computed minimum with a fixed dimension.
- `MergeOutlinePanel.getMinimumSize()` applies the same rule, so the outline of a three-sided diff and of the merge window keeps at least the two-sided minimum (R4). Its own computed minimum can be larger, for example with the engine dropdown and its extra toolbar actions.

200 px is wide enough for the header actions (the computed minimum is about 133 px) and for the summary line and the first words of a row, and narrow enough to leave the editors usable in a default splitter. The value is not a setting, as the outline's own width never was.

No change is needed in `insertOutlineSplitter`: the platform splitter already clamps both dragging and layout to the component minimum when there is room for both components. The lack-of-space strategy stays the platform default, so a window too narrow for both the editors and the 200 px panel shrinks the panel instead of hiding the editors.

### Remembered widths below the minimum

The splitter proportion is still read from `smartdiff.outline.proportion` when the splitter is created. When the layer lays the splitter out, the platform clamps the panel to its minimum, so a remembered proportion that would give the panel less than 200 px shows the panel at 200 px and gives the rest to the editors (R3). The stored proportion is left as it was; the user's next drag overwrites it. Minimising and restoring do not change the proportion either, so the restored panel is at least the minimum.

### States

The state an outline is in does not change the panel's minimum: `Loading`, a result (`ANALYZED` or `PARTIAL`), a fallback (`UNSUPPORTED`, `TOO_LARGE`, `FAILED`) and `NoEngine` all show the same panel, so they all keep the minimum (R2). The minimised strip is not the panel and keeps its own fixed 24 px width.

## Tests

| Requirement | Check |
| --- | --- |
| R1 | `OutlineIntegrationTest` `test outline 09 R1 the outline panel stops at its minimum width`: laying the splitter out with a proportion that would give the editors almost all of the width keeps the panel at `outlineMinimumWidth()` and gives the editors the rest |
| R2 | `OutlineIntegrationTest` `test outline 09 R2 the minimum holds for a fallback and while comparing`: the panel's minimum is the same before a result, while `Comparing…` and after a fallback result |
| R3 | `OutlineIntegrationTest` `test outline 09 R3 a remembered width below the minimum opens at the minimum`: a saved `smartdiff.outline.proportion` that is too small opens the panel at the minimum width |
| R4 | `MergeOutlineIntegrationTest` `test outline 09 R4 the merge outline keeps the same minimum width`: `MergeOutlinePanel.minimumSize.width` is at least `outlineMinimumWidth()` |

## Open questions

None.
