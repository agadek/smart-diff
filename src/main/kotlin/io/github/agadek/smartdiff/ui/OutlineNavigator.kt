package io.github.agadek.smartdiff.ui

import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
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
 * Shows a selected outline item in the diff editors (technical specification §10): each side's editor scrolls to the
 * item's range on that side and frames its lines, plus the item's detail spans.
 *
 * The frame is drawn over the diff's own colors rather than replacing them, so the change stays readable inside it,
 * and it is marked on the editor's scrollbar.
 */
internal class OutlineNavigator(private val viewer: TwosideTextDiffViewer) {

    private val highlighters = mutableListOf<RangeHighlighter>()

    /**
     * Highlights [item] on both sides and scrolls each side to it. Synchronized scrolling is paused meanwhile, so a moved
     * declaration shows on both sides; the user's next scroll synchronizes the editors again.
     */
    fun select(item: OutlineItem?) {
        clear()
        if (item == null || item.isGroup) return
        val sync = viewer.syncScrollSupport
        sync?.enterDisableScrollSection()
        try {
            show(viewer.editor1, item.oldRange, item.details.mapNotNull { it.old })
            show(viewer.editor2, item.newRange, item.details.mapNotNull { it.new })
        } finally {
            sync?.exitDisableScrollSection()
        }
    }

    /** Moves the caret into the new side's editor, or the old side's when the item is only there (Enter or double-click). */
    fun navigate(item: OutlineItem) {
        val (editor, range) = when {
            item.newRange != null -> viewer.editor2 to item.newRange
            item.oldRange != null -> viewer.editor1 to item.oldRange
            else -> return
        }
        editor.caretModel.moveToOffset(clamp(editor, range.start))
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        IdeFocusManager.getGlobalInstance().requestFocus(editor.contentComponent, true)
    }

    /** The highlighters of the selected item, for tests. */
    val shown: List<RangeHighlighter> get() = highlighters.toList()

    fun clear() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
    }

    private fun show(editor: Editor, range: TextRange?, details: List<TextRange>) {
        if (range != null) {
            frame(editor, range)
            // Without animation, so the scroll is done before synchronized scrolling resumes.
            DiffUtil.scrollEditor(editor, editor.document.getLineNumber(clamp(editor, range.start)), false)
        }
        details.forEach { highlight(editor, it, DETAIL, HighlighterTargetArea.EXACT_RANGE) }
    }

    private fun frame(editor: Editor, range: TextRange) {
        val highlighter = add(editor, range, null, HighlighterTargetArea.LINES_IN_RANGE)
        highlighter.customRenderer = Frame
        highlighter.setErrorStripeMarkColor(FRAME_COLOR)
        highlighter.isThinErrorStripeMark = false
    }

    private fun highlight(editor: Editor, range: TextRange, key: TextAttributesKey, area: HighlighterTargetArea) {
        add(editor, range, key, area)
    }

    private fun add(editor: Editor, range: TextRange, key: TextAttributesKey?, area: HighlighterTargetArea): RangeHighlighter {
        // The documents may have changed since the comparison; its next result replaces these ranges.
        val start = clamp(editor, range.start)
        val end = clamp(editor, range.end).coerceAtLeast(start)
        return editor.markupModel.addRangeHighlighter(key, start, end, HighlighterLayer.SELECTION - 1, area).also { highlighters += it }
    }

    /** A rounded frame with a light fill around the lines of the selected item, across the editor's visible width. */
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
        /** Orange, so the frame differs from the diff's green, blue and gray in light and dark themes. */
        val FRAME_COLOR: Color = JBColor(Color(0xE0, 0x7B, 0x00), Color(0xF2, 0xA0, 0x3D))
        val DETAIL: TextAttributesKey = EditorColors.SEARCH_RESULT_ATTRIBUTES
    }
}
