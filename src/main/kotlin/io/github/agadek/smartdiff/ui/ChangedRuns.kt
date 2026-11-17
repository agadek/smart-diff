package io.github.agadek.smartdiff.ui

import com.intellij.diff.fragments.DiffFragment
import com.intellij.diff.fragments.DiffFragmentImpl
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.fragments.LineFragmentImpl
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.TextRange
import java.util.BitSet

/**
 * The [changed runs][ChangeNode.changedRuns] the SMART Diff viewer highlights in place of the native word highlights
 * (declarations/10 R11 to R13): for each side, the 0-based lines owned only by nodes compared by tokens, and the runs
 * of those nodes. A line owns as in [HiddenLines]: a node's range and `details` minus its reported children's extents,
 * and the file region's `details`. A line that also belongs to a node not compared by tokens, such as an added or an
 * unanalyzed declaration, or to an unanalyzed range, keeps the native highlights.
 */
class ChangedRuns private constructor(
    private val old: BitSet,
    private val new: BitSet,
    private val runs: List<RangePair>,
    private val oldLength: Int,
    private val newLength: Int,
) {
    val isEmpty: Boolean get() = old.isEmpty && new.isEmpty

    /**
     * [fragment] with its word highlights replaced by the runs inside it, or null to keep the native fragment: when a
     * line of the change on a side lies outside the lines compared by tokens (R13), or the fragment does not fit the
     * compared texts. A change with lines on one side only, a pure insertion or deletion, keeps the native fragment
     * too: it has no word highlights there, and any inner fragment would make the platform draw its lines in the paler
     * colour of a change with inner differences (R12). A two-sided change with no run inside gets no word highlights
     * and keeps its line colour (R12).
     */
    fun fragment(fragment: LineFragment): LineFragment? {
        if (isEmpty) return null
        if (fragment.endOffset1 > oldLength || fragment.endOffset2 > newLength) return null
        val oldEmpty = fragment.startLine1 >= fragment.endLine1
        val newEmpty = fragment.startLine2 >= fragment.endLine2
        if (oldEmpty || newEmpty) return null
        if (!all(old, fragment.startLine1, fragment.endLine1) || !all(new, fragment.startLine2, fragment.endLine2)) return null
        return LineFragmentImpl(fragment, inner(fragment))
    }

    /**
     * One inner fragment per run part inside the change, relative to the change's start offsets. A run with both parts
     * inside the change pairs them; a part alone gets an empty range on the other side, placed after the last paired
     * part by the same distance as on its own side, so the native marker for an insertion or deletion stays near it.
     */
    private fun inner(fragment: LineFragment): List<DiffFragment> {
        val oldChange = TextRange(fragment.startOffset1, fragment.endOffset1)
        val newChange = TextRange(fragment.startOffset2, fragment.endOffset2)
        val parts = runs.mapNotNull { run ->
            val oldPart = run.old?.let { clip(it, oldChange) }
            val newPart = run.new?.let { clip(it, newChange) }
            if (oldPart == null && newPart == null) null else RangePair(oldPart, newPart)
        }
        // Anchors pair an old offset with a new offset: the change's start and each paired part's end.
        val anchors = mutableListOf(oldChange.start to newChange.start)
        parts.filter { it.old != null && it.new != null }.forEach { anchors += it.old!!.end to it.new!!.end }
        fun newPosition(oldOffset: Int): Int {
            val anchor = anchors.filter { it.first <= oldOffset }.maxByOrNull { it.first } ?: anchors.first()
            return (anchor.second + (oldOffset - anchor.first)).coerceIn(newChange.start, newChange.end)
        }
        fun oldPosition(newOffset: Int): Int {
            val anchor = anchors.filter { it.second <= newOffset }.maxByOrNull { it.second } ?: anchors.first()
            return (anchor.first + (newOffset - anchor.second)).coerceIn(oldChange.start, oldChange.end)
        }
        return parts.map { part ->
            val o = part.old ?: oldPosition(part.new!!.start).let { TextRange(it, it) }
            val n = part.new ?: newPosition(part.old!!.start).let { TextRange(it, it) }
            DiffFragmentImpl(o.start - oldChange.start, o.end - oldChange.start, n.start - newChange.start, n.end - newChange.start)
        }.sortedWith(compareBy<DiffFragment>({ it.startOffset1 }, { it.startOffset2 }))
    }

    private fun clip(range: TextRange, within: TextRange): TextRange? {
        val start = maxOf(range.start, within.start)
        val end = minOf(range.end, within.end)
        return if (start < end) TextRange(start, end) else null
    }

    private fun all(lines: BitSet, start: Int, end: Int): Boolean = start >= end || lines.nextClearBit(start) >= end

    override fun equals(other: Any?): Boolean =
        other is ChangedRuns && old == other.old && new == other.new && runs == other.runs &&
            oldLength == other.oldLength && newLength == other.newLength

    override fun hashCode(): Int = (31 * old.hashCode() + new.hashCode()) * 31 + runs.hashCode()

    companion object {
        val NONE = ChangedRuns(BitSet(), BitSet(), emptyList(), 0, 0)

        /** The runs of [result]'s nodes compared by tokens, over [oldText] and [newText] (declarations/10 details). */
        fun of(result: ComparisonResult, oldText: CharSequence, newText: CharSequence): ChangedRuns {
            val oldIndex = LineIndex(oldText)
            val newIndex = LineIndex(newText)
            val comparedOld = BitSet()
            val comparedNew = BitSet()
            val otherOld = BitSet()
            val otherNew = BitSet()
            val runs = mutableListOf<RangePair>()
            fun add(olds: List<TextRange?>, news: List<TextRange?>, node: ChangeNode) {
                olds.filterNotNull().forEach { oldIndex.mark(it, if (node.comparedByTokens) comparedOld else otherOld) }
                news.filterNotNull().forEach { newIndex.mark(it, if (node.comparedByTokens) comparedNew else otherNew) }
                if (node.comparedByTokens) runs += node.changedRuns
            }
            result.fileRegion?.let { region ->
                if (region.isChangedItself) add(region.details.map { it.old }, region.details.map { it.new }, region)
            }
            fun visit(node: ChangeNode) {
                if (node.isChangedItself) {
                    val children = node.children.filter { it.isReported }
                    val olds = HiddenLines.subtract(listOf(node.oldRange) + node.details.map { it.old }, children.flatMap { HiddenLines.extent(it).first })
                    val news = HiddenLines.subtract(listOf(node.newRange) + node.details.map { it.new }, children.flatMap { HiddenLines.extent(it).second })
                    add(olds, news, node)
                }
                node.children.forEach(::visit)
            }
            result.changes.forEach(::visit)
            result.unanalyzed.forEach { range ->
                range.old?.let { oldIndex.mark(it, otherOld) }
                range.new?.let { newIndex.mark(it, otherNew) }
            }
            comparedOld.andNot(otherOld)
            comparedNew.andNot(otherNew)
            if (comparedOld.isEmpty && comparedNew.isEmpty) return NONE
            return ChangedRuns(comparedOld, comparedNew, runs, oldText.length, newText.length)
        }
    }
}
