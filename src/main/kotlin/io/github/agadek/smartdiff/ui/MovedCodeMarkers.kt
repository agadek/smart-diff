package io.github.agadek.smartdiff.ui

import com.intellij.diff.FrameDiffTool
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.JBColor
import io.github.agadek.smartdiff.engine.api.TextRange
import javax.swing.Icon

/** One moved row to mark in both editors (outline/13 R2). */
data class MovedTarget(
    val path: OutlinePath,
    val title: String,
    val oldRange: TextRange,
    val newRange: TextRange,
)

/** A gutter icon on a moved declaration's first line, shared by both sides (outline/13 R4, R5, R7). */
internal class MovedCodeGutterIcon(
    private val target: MovedTarget,
    private val newSide: Boolean,
    tooltip: String,
    private val onClick: (OutlinePath) -> Unit,
) : GutterIconRenderer() {

    private val tooltipTextValue = tooltip

    override fun getIcon(): Icon = AllIcons.Actions.MoveTo2

    override fun getAlignment(): GutterIconRenderer.Alignment = GutterIconRenderer.Alignment.LEFT

    override fun getTooltipText(): String = tooltipTextValue

    override fun getClickAction(): AnAction = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            onClick(target.path)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is MovedCodeGutterIcon && newSide == other.newSide && target == other.target

    override fun hashCode(): Int = 31 * target.hashCode() + if (newSide) 1 else 0
}

/**
 * The gutter icons and scrollbar marks of the moved rows the outline shows (outline/13). The targets come from the
 * shown rows, so every filter of the outline applies to them without repeating its rules. The highlighters carry no
 * text attributes: they add a marker, not a colour, and leave the diff's own colours and the line text alone.
 */
internal class MovedCodeMarkers(
    private val viewer: FrameDiffTool.DiffViewer,
    private val onClick: (OutlinePath) -> Unit,
) {

    private val highlighters = mutableListOf<RangeHighlighter>()

    /** Replaces the markers with those of [targets]; a viewer that is not a two-sided text diff gets none. */
    fun show(targets: List<MovedTarget>) {
        clear()
        val two = viewer as? TwosideTextDiffViewer ?: return
        for (target in targets) {
            add(two.editor1, target.oldRange, target, newSide = false, otherRange = target.newRange, otherEditor = two.editor2)
            add(two.editor2, target.newRange, target, newSide = true, otherRange = target.oldRange, otherEditor = two.editor1)
        }
    }

    fun clear() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
    }

    /** The highlighters in place, for tests. */
    internal val shown: List<RangeHighlighter> get() = highlighters.toList()

    private fun add(
        editor: Editor,
        range: TextRange,
        target: MovedTarget,
        newSide: Boolean,
        otherRange: TextRange,
        otherEditor: Editor,
    ) {
        val start = range.start.coerceIn(0, editor.document.textLength)
        val end = range.end.coerceIn(start, editor.document.textLength)
        if (start == end) return
        val otherLine = otherEditor.document.getLineNumber(otherRange.start.coerceIn(0, otherEditor.document.textLength)) + 1
        val tooltip = if (newSide) "${target.title}: Moved from line $otherLine" else "${target.title}: Moved to line $otherLine"
        val highlighter = editor.markupModel.addRangeHighlighter(
            start,
            end,
            HighlighterLayer.ADDITIONAL_SYNTAX,
            null,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        highlighter.gutterIconRenderer = MovedCodeGutterIcon(target, newSide, tooltip, onClick)
        highlighter.setErrorStripeMarkColor(MOVED_MARK)
        highlighter.isThinErrorStripeMark = true
        highlighter.errorStripeTooltip = tooltip
        highlighters += highlighter
    }

    companion object {
        /** Muted blue, with a lighter value for light themes and a brighter one for dark themes (R6). */
        val MOVED_MARK: JBColor = JBColor(0x4C78A8, 0x82B0DC)

        /**
         * The moved rows among [items], children included, in new-side line order. Group rows and rows without both
         * ranges cannot be marked; a row listed twice gets one target per row (outline/13 details).
         */
        fun targets(items: List<OutlineItem>): List<MovedTarget> {
            val targets = mutableListOf<MovedTarget>()
            fun visit(level: List<OutlineItem>, ancestors: List<OutlineItem>) {
                for (item in level) {
                    val path = ancestors + item
                    if (!item.isGroup && item.moved && item.oldRange != null && item.newRange != null) {
                        targets += MovedTarget(OutlinePath.of(path), item.title, item.oldRange!!, item.newRange!!)
                    }
                    visit(item.children, path)
                }
            }
            visit(items, emptyList())
            return targets.sortedBy { it.newRange.start }
        }
    }
}
