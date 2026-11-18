package io.github.agadek.smartdiff.ui

import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
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
internal class OutlineNavigator(
    private val controller: OutlineController,
    private val oldEditor: Editor?,
    private val newEditor: Editor?,
    private val scrollSupport: () -> com.intellij.diff.tools.util.SyncScrollSupport.Support?,
    private val syncScrollEnabled: () -> Boolean,
) {

    private val highlighters = mutableListOf<RangeHighlighter>()
    private val frames = mutableListOf<RangeHighlighter>()
    private var frameSync: FrameScrollSync? = null

    /**
     * Highlights [item] on both sides and, when [scroll], scrolls each side to it. When both ranges are present while
     * Synchronize scrolling is on and [scroll] is true, the platform's sync stays paused and [frameSync] keeps the
     * frames aligned until one leaves view or the selection is released; otherwise the row is only framed.
     */
    fun select(item: OutlineItem?, scroll: Boolean = true) {
        clear()
        if (item == null || item.isGroup) return
        val oldRanges = listOfNotNull(item.oldRange) + item.frames.mapNotNull { it.old }
        val newRanges = listOfNotNull(item.newRange) + item.frames.mapNotNull { it.new }
        val follows = scroll && oldRanges.size == 1 && newRanges.size == 1 && oldEditor != null && newEditor != null &&
            followsSynchronizedScrolling()
        // declarations/10 R17: a row built from a node also highlights its changed runs, while the setting is on.
        val runs = item.node?.changedRuns.orEmpty().takeIf { SmartDiffSettings.getInstance().highlightChangedTokens }.orEmpty()
        val highlighted = item.details + runs
        val showEditors = {
            withSyncDisabled {
                oldEditor?.let { show(it, oldRanges, highlighted.mapNotNull { it.old }.distinct(), scroll) }
                newEditor?.let { show(it, newRanges, highlighted.mapNotNull { it.new }.distinct(), scroll) }
            }
        }
        if (scroll) controller.withSuppressedCaretFollow(showEditors) else showEditors()
        if (follows && frames.size == 2) {
            frameSync = FrameScrollSync(
                listOf(oldEditor!!, newEditor!!),
                frames.toList(),
                scrollSupport()?.asScrollPause(),
            ).also { it.start() }
        }
    }

    /** Stops keeping the pair aligned, for a document change; the highlighters stay until the next result. */
    fun releaseScrollSync() {
        frameSync?.stop()
        frameSync = null
    }

    /**
     * Moves the caret into the new side's editor, or the old side's when the item is only there (Enter or double-click).
     * Does nothing when that side has no editor; it never moves the caret using a range from the other document.
     */
    fun navigate(item: OutlineItem) {
        val (editor, range) = when {
            item.newRange != null && newEditor != null -> newEditor to item.newRange
            item.oldRange != null && oldEditor != null -> oldEditor to item.oldRange
            else -> return
        }
        editor.caretModel.moveToOffset(clamp(editor, range.start))
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        IdeFocusManager.getGlobalInstance().requestFocus(editor.contentComponent, true)
    }

    /** The highlighters of the selected item, for tests. */
    val shown: List<RangeHighlighter> get() = highlighters.toList()

    fun clear() {
        releaseScrollSync()
        highlighters.forEach { it.dispose() }
        highlighters.clear()
        frames.clear()
    }

    /** Runs [action] with synchronized scrolling paused, for this selection's own scrolls and for expanding (outline/14). */
    internal fun withSyncDisabled(action: () -> Unit) {
        val support = scrollSupport()
        support?.enterDisableScrollSection()
        try {
            action()
        } finally {
            support?.exitDisableScrollSection()
        }
    }

    /** Whether the platform is currently synchronizing this viewer's scrolling for the user. */
    private fun followsSynchronizedScrolling(): Boolean = syncScrollEnabled()

    private fun show(editor: Editor, ranges: List<TextRange>, details: List<TextRange>, scroll: Boolean) {
        ranges.forEachIndexed { index, range ->
            frame(editor, range)
            if (scroll && index == 0) {
                // Without animation, so the scroll is done before synchronized scrolling resumes.
                DiffUtil.scrollEditor(editor, editor.document.getLineNumber(clamp(editor, range.start)), false)
            }
        }
        details.forEach { highlight(editor, it, DETAIL, HighlighterTargetArea.EXACT_RANGE) }
    }

    private fun frame(editor: Editor, range: TextRange) {
        val highlighter = add(editor, range, null, HighlighterTargetArea.LINES_IN_RANGE)
        highlighter.customRenderer = Frame
        highlighter.setErrorStripeMarkColor(FRAME_COLOR)
        highlighter.isThinErrorStripeMark = false
        frames += highlighter
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
