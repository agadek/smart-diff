package io.github.agadek.smartdiff.ui

import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.wm.IdeFocusManager
import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * Shows a selected outline item in the diff editors (technical specification §10): each side's editor scrolls to the
 * item's range on that side and highlights it, plus the item's detail spans.
 */
internal class OutlineNavigator(private val viewer: TwosideTextDiffViewer) {

    private val highlighters = mutableListOf<RangeHighlighter>()

    /** Highlights [item] on both sides and scrolls to it; the new side scrolls last, so it wins when scrolling is synchronized. */
    fun select(item: OutlineItem?) {
        clear()
        if (item == null || item.isGroup) return
        show(viewer.editor1, item.oldRange, item.details.mapNotNull { it.old })
        show(viewer.editor2, item.newRange, item.details.mapNotNull { it.new })
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
            highlight(editor, range, SELECTED, HighlighterTargetArea.LINES_IN_RANGE)
            editor.scrollingModel.scrollTo(editor.offsetToLogicalPosition(clamp(editor, range.start)), ScrollType.CENTER)
        }
        details.forEach { highlight(editor, it, DETAIL, HighlighterTargetArea.EXACT_RANGE) }
    }

    private fun highlight(editor: Editor, range: TextRange, key: TextAttributesKey, area: HighlighterTargetArea) {
        // The documents may have changed since the comparison; its next result replaces these ranges.
        val start = clamp(editor, range.start)
        val end = clamp(editor, range.end).coerceAtLeast(start)
        highlighters += editor.markupModel.addRangeHighlighter(key, start, end, HighlighterLayer.SELECTION - 1, area)
    }

    private fun clamp(editor: Editor, offset: Int) = offset.coerceIn(0, editor.document.textLength)

    private companion object {
        val SELECTED: TextAttributesKey = EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES
        val DETAIL: TextAttributesKey = EditorColors.SEARCH_RESULT_ATTRIBUTES
    }
}
