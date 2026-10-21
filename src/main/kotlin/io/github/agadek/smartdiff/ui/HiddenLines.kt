package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.TextRange
import java.util.BitSet

/**
 * The 0-based lines on each side that belong only to changes the filter hides (technical specification §10), for the
 * SMART Diff viewer to fade its marks there. A line belongs to the change whose own extent covers it: its range and
 * `details`, minus the extents of its reported children. The file region's own extent is its `details`, because its
 * range spans the whole file. A line that also belongs to a shown change or to an unanalyzed range is never hidden.
 */
class HiddenLines private constructor(private val old: BitSet, private val new: BitSet) {

    val isEmpty: Boolean get() = old.isEmpty && new.isEmpty

    /**
     * Whether a native change spanning lines `[oldStart, oldEnd)` and `[newStart, newEnd)` lies entirely on hidden
     * lines; an empty side, such as the old side of an insertion, does not count.
     */
    fun covers(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int): Boolean {
        if (oldStart >= oldEnd && newStart >= newEnd) return false
        return all(old, oldStart, oldEnd) && all(new, newStart, newEnd)
    }

    override fun equals(other: Any?): Boolean = other is HiddenLines && old == other.old && new == other.new

    override fun hashCode(): Int = 31 * old.hashCode() + new.hashCode()

    private fun all(lines: BitSet, start: Int, end: Int): Boolean = start >= end || lines.nextClearBit(start) >= end

    companion object {
        val NONE = HiddenLines(BitSet(), BitSet())

        fun of(result: ComparisonResult, hidden: Set<ChangeType>, oldText: CharSequence, newText: CharSequence): HiddenLines {
            if (hidden.isEmpty()) return NONE
            val sides = Sides(LineIndex(oldText), LineIndex(newText))
            result.fileRegion?.let { region ->
                if (region.isChangedItself) sides.add(region.details.map { it.old }, region.details.map { it.new }, OutlineModel.isHidden(region, hidden))
            }
            fun visit(node: ChangeNode) {
                if (node.isChangedItself) {
                    val children = node.children.filter { it.isReported }
                    val olds = subtract(listOf(node.oldRange) + node.details.map { it.old }, children.flatMap { extent(it).first })
                    val news = subtract(listOf(node.newRange) + node.details.map { it.new }, children.flatMap { extent(it).second })
                    sides.add(olds, news, OutlineModel.isHidden(node, hidden))
                }
                node.children.forEach(::visit)
            }
            result.changes.forEach(::visit)
            sides.add(result.unanalyzed.map { it.old }, result.unanalyzed.map { it.new }, hidden = false)
            return sides.hiddenLines()
        }

        private fun extent(node: ChangeNode): Pair<List<TextRange?>, List<TextRange?>> =
            (listOf(node.oldRange) + node.details.map { it.old }) to (listOf(node.newRange) + node.details.map { it.new })

        /** [ranges] with the parts covered by [holes] removed. */
        private fun subtract(ranges: List<TextRange?>, holes: List<TextRange?>): List<TextRange> {
            var pieces = ranges.filterNotNull()
            for (hole in holes.filterNotNull()) {
                pieces = pieces.flatMap { piece ->
                    if (hole.end <= piece.start || hole.start >= piece.end) listOf(piece)
                    else listOfNotNull(
                        TextRange(piece.start, hole.start).takeIf { hole.start > piece.start },
                        TextRange(hole.end, piece.end).takeIf { hole.end < piece.end },
                    )
                }
            }
            return pieces
        }
    }

    private class Sides(private val oldIndex: LineIndex, private val newIndex: LineIndex) {
        private val hiddenOld = BitSet()
        private val hiddenNew = BitSet()
        private val shownOld = BitSet()
        private val shownNew = BitSet()

        fun add(old: List<TextRange?>, new: List<TextRange?>, hidden: Boolean) {
            old.filterNotNull().forEach { oldIndex.mark(it, if (hidden) hiddenOld else shownOld) }
            new.filterNotNull().forEach { newIndex.mark(it, if (hidden) hiddenNew else shownNew) }
        }

        fun hiddenLines(): HiddenLines {
            hiddenOld.andNot(shownOld)
            hiddenNew.andNot(shownNew)
            return if (hiddenOld.isEmpty && hiddenNew.isEmpty) NONE else HiddenLines(hiddenOld, hiddenNew)
        }
    }

    /** Maps offsets of one text to 0-based lines. */
    private class LineIndex(private val text: CharSequence) {
        private val starts: IntArray = buildList {
            add(0)
            for (i in text.indices) if (text[i] == '\n') add(i + 1)
        }.toIntArray()

        fun line(offset: Int): Int {
            val i = starts.binarySearch(offset)
            return if (i >= 0) i else -i - 2
        }

        /**
         * Marks every line [range] touches, except a line it touches only at its line break, as a declaration's leading
         * whitespace touches the line before it; an empty range marks nothing.
         */
        fun mark(range: TextRange, lines: BitSet) {
            val start = if (range.start < text.length && text[range.start] == '\n') range.start + 1 else range.start
            if (start >= range.end) return
            lines.set(line(start), line(range.end - 1) + 1)
        }
    }
}
