package io.github.agadek.smartdiff.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * Expands the collapsed unchanged fragments of a changed declaration on both sides of a two-sided diff (outline/14), so
 * the reviewer reads the whole declaration without opening each fragment by hand. Fragments outside the declaration stay
 * collapsed.
 *
 * The row's ranges on each side are the ones [OutlineNavigator.select] frames: `oldRange`/`newRange` plus each entry of
 * `frames`, so a relationship row covers every declaration it links (R7). A group row and the file region give no ranges
 * (R8). Ranges are clamped to the document, because the documents may have changed since the comparison.
 *
 * Only `FoldRegion.isExpanded` is set, inside `FoldingModel.runBatchFoldingOperation`; no region is added, removed or
 * split, and the diff's settings are not touched (R10). The platform's folding listener expands the paired region on the
 * other side, so a fragment expands on both sides at once (R4).
 */
internal object ExpandDeclaration {

    /** The row's old-side ranges, or none for a group row or the file region (R8). */
    fun oldRanges(item: OutlineItem): List<TextRange> = ranges(item, old = true)

    /** The row's new-side ranges, or none for a group row or the file region (R8). */
    fun newRanges(item: OutlineItem): List<TextRange> = ranges(item, old = false)

    private fun ranges(item: OutlineItem, old: Boolean): List<TextRange> {
        if (item.isGroup || item.kind == ItemKind.FILE_REGION) return emptyList()
        val primary = if (old) item.oldRange else item.newRange
        return listOfNotNull(primary) + item.frames.mapNotNull { if (old) it.old else it.new }
    }

    /**
     * The collapsed unchanged fragments of [editor] whose lines overlap any of [ranges] (R3). A fragment only partly in
     * the declaration is selected and expands whole (R5); a fragment that overlaps none of the ranges is left alone (R6).
     */
    fun fragmentsToExpand(editor: Editor, ranges: List<TextRange>): List<FoldRegion> {
        if (ranges.isEmpty()) return emptyList()
        val document = editor.document
        val lineRanges = ranges.map { range ->
            val start = range.start.coerceIn(0, document.textLength)
            val end = range.end.coerceIn(0, document.textLength)
            document.getLineNumber(start) to document.getLineNumber(maxOf(start, end - 1))
        }
        return UnchangedFragments.collapsed(editor).filter { region ->
            val first = document.getLineNumber(region.startOffset)
            val last = document.getLineNumber(region.endOffset)
            lineRanges.any { (start, end) -> first <= end && start <= last }
        }
    }

    /** Whether [item] has a collapsed fragment to expand on either side, so the action is shown only then (R8). */
    fun wouldExpand(oldEditor: Editor?, newEditor: Editor?, item: OutlineItem): Boolean =
        (oldEditor != null && fragmentsToExpand(oldEditor, oldRanges(item)).isNotEmpty()) ||
            (newEditor != null && fragmentsToExpand(newEditor, newRanges(item)).isNotEmpty())

    /**
     * Expands every collapsed unchanged fragment that overlaps [item]'s range on the old or the new side, on both editors
     * (R3 to R6). Each editor runs one batch folding operation that sets `isExpanded` on its selected regions.
     */
    fun expand(oldEditor: Editor?, newEditor: Editor?, item: OutlineItem) {
        expandSide(oldEditor, oldRanges(item))
        expandSide(newEditor, newRanges(item))
    }

    private fun expandSide(editor: Editor?, ranges: List<TextRange>) {
        if (editor == null) return
        val regions = fragmentsToExpand(editor, ranges)
        if (regions.isEmpty()) return
        editor.foldingModel.runBatchFoldingOperation {
            regions.forEach { it.isExpanded = true }
        }
    }
}
