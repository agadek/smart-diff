package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.tools.util.side.ThreesideTextDiffViewer
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.ThreeSide
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import io.github.agadek.smartdiff.engine.api.TextRange
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * Shows a selected merge outline row in the three panes (merge outline spec 02 R16, 03 R5): the left and right
 * ranges in their editors, and the base range in the middle editor of a three-sided diff.
 */
internal class MergeOutlineNavigator(private val viewer: ThreesideTextDiffViewer) {

    private val highlighters = mutableListOf<RangeHighlighter>()

    fun select(item: MergeOutlineItem?) {
        clear()
        if (item == null || item.isGroup) return
        withSyncDisabled {
            show(viewer.getEditor(ThreeSide.LEFT), item.leftRange)
            show(viewer.getEditor(ThreeSide.BASE), item.baseRange)
            show(viewer.getEditor(ThreeSide.RIGHT), item.rightRange)
        }
    }

    /**
     * Runs [action] with synchronized scrolling paused, like the two-sided outline does. The platform exposes the
     * three-sided sync support only as a protected internal field, so it is read defensively and invoked by name; without
     * it the panes still frame the selected row.
     */
    private fun withSyncDisabled(action: () -> Unit) {
        val sync = syncSupport()
        sync?.let { invoke(it, "enterDisableScrollSection") }
        try {
            action()
        } finally {
            sync?.let { invoke(it, "exitDisableScrollSection") }
        }
    }

    private fun syncSupport(): Any? = try {
        val field = ThreesideTextDiffViewer::class.java.getDeclaredField("mySyncScrollSupport")
        field.isAccessible = true
        field.get(viewer)
    } catch (e: ReflectiveOperationException) {
        null
    }

    private fun invoke(target: Any, method: String) {
        try {
            target.javaClass.getMethod(method).invoke(target)
        } catch (e: ReflectiveOperationException) {
            // The public outline still works when the platform changes its internal sync-scroll shape.
        }
    }

    /** Moves the caret into the right pane, or the nearest present pane when the right one is absent. */
    fun navigate(item: MergeOutlineItem) {
        val (editor, range) = when {
            item.rightRange != null -> viewer.getEditor(ThreeSide.RIGHT) to item.rightRange
            item.baseRange != null -> viewer.getEditor(ThreeSide.BASE) to item.baseRange
            item.leftRange != null -> viewer.getEditor(ThreeSide.LEFT) to item.leftRange
            else -> return
        }
        editor.caretModel.moveToOffset(clamp(editor, range.start))
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        IdeFocusManager.getGlobalInstance().requestFocus(editor.contentComponent, true)
    }

    /** The highlighters of the selected row, for tests. */
    val shown: List<RangeHighlighter> get() = highlighters.toList()

    fun clear() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
    }

    private fun show(editor: Editor, range: TextRange?) {
        if (range == null) return
        frame(editor, range)
        DiffUtil.scrollEditor(editor, editor.document.getLineNumber(clamp(editor, range.start)), false)
    }

    private fun frame(editor: Editor, range: TextRange) {
        val highlighter = add(editor, range, null, HighlighterTargetArea.LINES_IN_RANGE)
        highlighter.customRenderer = Frame
        highlighter.setErrorStripeMarkColor(FRAME_COLOR)
        highlighter.isThinErrorStripeMark = false
    }

    private fun add(editor: Editor, range: TextRange, key: TextAttributesKey?, area: HighlighterTargetArea): RangeHighlighter {
        val start = clamp(editor, range.start)
        val end = clamp(editor, range.end).coerceAtLeast(start)
        return editor.markupModel.addRangeHighlighter(key, start, end, HighlighterLayer.SELECTION - 1, area).also { highlighters += it }
    }

    /** A rounded frame with a light fill around the selected lines, matching the two-sided outline. */
    private object Frame : CustomHighlighterRenderer {
        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            if (!highlighter.isValid) return
            val document = editor.document
            val first = document.getLineNumber(highlighter.startOffset)
            val last = document.getLineNumber(maxOf(highlighter.startOffset, highlighter.endOffset - 1))
            val top = editor.logicalPositionToXY(LogicalPosition(first, 0)).y
            val bottom = editor.logicalPositionToXY(LogicalPosition(last, 0)).y + editor.lineHeight
            val area = editor.scrollingModel.visibleArea
            val inset = JBUIScale.scale(2)
            val arc = JBUIScale.scale(8)
            val x = area.x + inset
            val width = area.width - 2 * inset - 1
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ColorUtil.withAlpha(FRAME_COLOR, 0.12)
                g2.fillRoundRect(x, top, width, bottom - top - 1, arc, arc)
                g2.color = FRAME_COLOR
                g2.stroke = BasicStroke(JBUIScale.scale(2f))
                g2.drawRoundRect(x, top, width, bottom - top - 1, arc, arc)
            } finally {
                g2.dispose()
            }
        }
    }

    private fun clamp(editor: Editor, offset: Int) = offset.coerceIn(0, editor.document.textLength)

    private companion object {
        val FRAME_COLOR: Color = JBColor(Color(0xE0, 0x7B, 0x00), Color(0xF2, 0xA0, 0x3D))
    }
}
