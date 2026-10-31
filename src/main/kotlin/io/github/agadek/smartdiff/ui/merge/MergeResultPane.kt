package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import io.github.agadek.smartdiff.engine.api.TextRange
import javax.swing.Timer

/**
 * The merge window's result pane as an outline selection (merge window spec R8-R11, R15, R16): it places one range
 * marker per base range on the result document, frames the selected declaration from the marker, and reports "not
 * tracked" when an edit removed it. It never edits the result.
 */
internal class MergeResultPane(
    private val project: Project,
    private val baseDocument: Document,
    private val resultDocument: Document,
) : MergeOutlineSelection, Disposable {

    private val markers = LinkedHashMap<TextRange, RangeMarker>()
    private val highlighters = mutableListOf<RangeHighlighter>()
    private var selected: MergeOutlineItem? = null
    private var preparedRanges: List<TextRange> = emptyList()
    private var prepared = false
    private var mappingReady = false
    private var released = false

    /** Lets the panel update its note when an edit changes whether the selected row is still tracked. */
    var onSelectionChanged: (() -> Unit)? = null
    private val debounce = Timer(MAPPING_DELAY_MILLIS) { mapNow() }.apply { isRepeats = false }
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (!prepared) return
            if (mappingReady) {
                render(scroll = false)
                onSelectionChanged?.invoke()
            } else {
                debounce.restart()
            }
        }
    }

    /** Whether the result has been mapped, for tests that select a row after the window settles. */
    internal val isMapped: Boolean get() = mappingReady

    /** Whether dispose released the result document and its markers, for the closing test. */
    internal val isReleased: Boolean get() = released

    init {
        resultDocument.addDocumentListener(documentListener, this)
    }

    /** Starts mapping once the viewer has set the result to the base, or to the auto-applied result after that. */
    fun prepare(ranges: List<TextRange>) {
        preparedRanges = ranges.distinct()
        prepared = true
        if (mappingReady) return
        if (resultDocument.text == baseDocument.text) mapNow() else debounce.restart()
    }

    override fun select(item: MergeOutlineItem?) {
        selected = item?.takeUnless { it.isGroup }
        render(scroll = true)
    }

    override fun navigate(item: MergeOutlineItem) {
        selected = item
        render(scroll = true)
        val editor = editor() ?: return
        marker(item)?.let { editor.caretModel.moveToOffset(it.startOffset) }
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        IdeFocusManager.getGlobalInstance().requestFocus(editor.contentComponent, true)
    }

    override fun clear() {
        selected = null
        clearHighlighters()
    }

    /** The panel's note for [item] when the result no longer tracks its declaration (merge window spec R10, R11). */
    fun note(item: MergeOutlineItem?): String {
        if (item == null || item.isGroup || !mappingReady) return ""
        val range = item.baseRange ?: return "not tracked"
        return if (markers[range]?.isValid == true) "" else "not tracked"
    }

    private fun mapNow() {
        debounce.stop()
        if (!prepared || mappingReady) return
        val mapping = MergeResultMapping.create(baseDocument, resultDocument)
        mapping?.let { map ->
            preparedRanges.forEach { range ->
                map.map(range)?.let { mapped ->
                    markers[range] = resultDocument.createRangeMarker(mapped.start, mapped.end)
                }
            }
        }
        mappingReady = true
        render(scroll = false)
        onSelectionChanged?.invoke()
    }

    private fun render(scroll: Boolean) {
        clearHighlighters()
        val item = selected ?: return
        val marker = marker(item) ?: return
        val editor = editor() ?: return
        val start = marker.startOffset.coerceIn(0, editor.document.textLength)
        val end = marker.endOffset.coerceIn(start, editor.document.textLength)
        editor.markupModel
            .addRangeHighlighter(null, start, end, HighlighterLayer.SELECTION - 1, HighlighterTargetArea.LINES_IN_RANGE)
            .also {
                it.customRenderer = MergeOutlineFrame
                it.setErrorStripeMarkColor(MergeOutlineFrame.COLOR)
                it.isThinErrorStripeMark = false
                highlighters += it
            }
        if (scroll) DiffUtil.scrollEditor(editor, editor.document.getLineNumber(start), false)
    }

    private fun marker(item: MergeOutlineItem): RangeMarker? =
        item.baseRange?.let(markers::get)?.takeIf { it.isValid }

    private fun editor(): Editor? = EditorFactory.getInstance().getEditors(resultDocument, project).firstOrNull()

    private fun clearHighlighters() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
    }

    override fun dispose() {
        released = true
        debounce.stop()
        resultDocument.removeDocumentListener(documentListener)
        clearHighlighters()
        markers.values.forEach { it.dispose() }
        markers.clear()
        preparedRanges = emptyList()
        prepared = false
        selected = null
        mappingReady = false
        onSelectionChanged = null
    }

    private companion object {
        const val MAPPING_DELAY_MILLIS = 300
    }
}

/**
 * Maps a declaration's base range into the merge window's result (merge window spec R8, R9, R11). When the result
 * equals the base the offsets are the same; otherwise a line diff shifts the range through unchanged lines, and a
 * declaration inside changed lines has no range at all.
 */
internal class MergeResultMapping private constructor(
    private val baseDocument: Document,
    private val resultDocument: Document,
    private val fragments: List<LineFragment>,
    private val sameText: Boolean,
) {

    fun map(range: TextRange): TextRange? {
        if (sameText) return range.coerceInDocuments()
        if (range.start > range.end) return null
        val start = range.start.coerceIn(0, baseDocument.textLength)
        val end = range.end.coerceIn(start, baseDocument.textLength)
        if (touchesChangedLine(start, end)) return null
        val mappedStart = mapStart(start) ?: return null
        val mappedEnd = mapEnd(end) ?: return null
        return TextRange(mappedStart, mappedEnd.coerceAtLeast(mappedStart))
    }

    private fun TextRange.coerceInDocuments(): TextRange =
        TextRange(start.coerceIn(0, resultDocument.textLength), end.coerceIn(start.coerceIn(0, resultDocument.textLength), resultDocument.textLength))

    private fun touchesChangedLine(start: Int, end: Int): Boolean {
        val firstLine = baseDocument.getLineNumber(start)
        val lastLine = baseDocument.getLineNumber(if (end > start) end - 1 else start)
        return fragments.any { it.startLine1 <= lastLine && it.endLine1 > firstLine }
    }

    private fun mapStart(offset: Int): Int? {
        val line = baseDocument.getLineNumber(offset)
        if (changed(line)) return null
        return position(line, offset - baseDocument.getLineStartOffset(line))
    }

    /** An end at a line start belongs to the previous line, so trailing line breaks map with the unchanged text. */
    private fun mapEnd(offset: Int): Int? {
        if (offset == 0) return 0
        val line = baseDocument.getLineNumber(offset)
        if (offset == baseDocument.getLineStartOffset(line)) {
            val previous = line - 1
            if (changed(previous)) return null
            val resultLine = mapLine(previous) ?: return null
            return resultDocument.getLineEndOffset(resultLine)
        }
        if (changed(line)) return null
        return position(line, offset - baseDocument.getLineStartOffset(line))
    }

    private fun changed(line: Int): Boolean = fragments.any { it.startLine1 <= line && it.endLine1 > line }

    private fun position(line: Int, column: Int): Int? {
        val resultLine = mapLine(line) ?: return null
        val start = resultDocument.getLineStartOffset(resultLine)
        return (start + column).coerceAtMost(resultDocument.getLineEndOffset(resultLine))
    }

    /** The result line for an unchanged base line, from the line deltas of the changed fragments before it. */
    private fun mapLine(line: Int): Int? {
        if (line < 0 || line >= baseDocument.lineCount) return null
        var delta = 0
        for (fragment in fragments) {
            if (fragment.endLine1 <= line) {
                delta += (fragment.endLine2 - fragment.startLine2) - (fragment.endLine1 - fragment.startLine1)
            } else {
                break
            }
        }
        return (line + delta).coerceIn(0, resultDocument.lineCount - 1)
    }

    companion object {
        fun create(baseDocument: Document, resultDocument: Document): MergeResultMapping? {
            if (baseDocument.text == resultDocument.text) {
                return MergeResultMapping(baseDocument, resultDocument, emptyList(), sameText = true)
            }
            return try {
                val fragments = ComparisonManager.getInstance().compareLines(
                    baseDocument.immutableCharSequence,
                    resultDocument.immutableCharSequence,
                    ComparisonPolicy.DEFAULT,
                    DumbProgressIndicator.INSTANCE,
                )
                MergeResultMapping(baseDocument, resultDocument, fragments, sameText = false)
            } catch (e: DiffTooBigException) {
                null
            } catch (e: RuntimeException) {
                // Without a mapping every row is "not tracked", rather than failing the outline.
                null
            }
        }
    }
}
