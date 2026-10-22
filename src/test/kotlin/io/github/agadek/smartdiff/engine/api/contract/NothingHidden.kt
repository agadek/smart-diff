package io.github.agadek.smartdiff.engine.api.contract

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * The "nothing hidden" rule (technical specification §3.1 rule 6), checked without depending on one diff algorithm's
 * choice of alignment.
 *
 * A character is covered when it lies in a range the outline shows as a change: the ranges and details of every node
 * that is a change itself (a container shown only because of its children covers nothing), the file region, and the
 * unanalyzed ranges. The rule holds when some character-level alignment of the two texts matches every uncovered
 * character, so that every differing character is covered.
 */
object NothingHidden {

    /** Returns null when the rule holds, otherwise a description of the first uncovered difference found. */
    fun violation(oldText: CharSequence, newText: CharSequence, result: ComparisonResult): String? {
        val oldCovered = BooleanArray(oldText.length)
        val newCovered = BooleanArray(newText.length)
        fun cover(range: TextRange?, covered: BooleanArray) {
            if (range != null) for (i in range.start until minOf(range.end, covered.size)) covered[i] = true
        }
        fun cover(node: ChangeNode, always: Boolean) {
            if (always || node.isChangedItself) {
                cover(node.oldRange, oldCovered)
                cover(node.newRange, newCovered)
            }
            node.details.forEach { cover(it.old, oldCovered); cover(it.new, newCovered) }
            node.children.forEach { cover(it, always = false) }
        }
        result.fileRegion?.let { cover(it, always = true) }
        result.changes.forEach { cover(it, always = false) }
        result.unanalyzed.forEach { cover(it.old, oldCovered); cover(it.new, newCovered) }

        return if (alignable(oldText, oldCovered, newText, newCovered)) null else describe(oldText, oldCovered, newText, newCovered)
    }

    /**
     * Whether an alignment exists in which every uncovered character is matched with an equal character on the other
     * side. Covered characters may be matched or left unmatched.
     *
     * A path through the grid of (old offset, new offset) from the start to the end of both texts, which may skip a
     * covered character on either side or match two equal characters. Row by row over the old text, it keeps the new
     * offsets reachable so far as sorted intervals: skipping covered new characters fills runs, so a row is a few
     * intervals and points rather than a full row of the table, and large files with local changes check in about
     * linear time. Matches the full dynamic programming exactly (`NothingHiddenTest`).
     */
    internal fun alignable(old: CharSequence, oldCovered: BooleanArray, new: CharSequence, newCovered: BooleanArray): Boolean {
        val m = new.length
        // runEnd[j]: the furthest offset reachable from j by skipping covered new characters.
        val runEnd = IntArray(m + 1)
        runEnd[m] = m
        for (j in m - 1 downTo 0) runEnd[j] = if (newCovered[j]) runEnd[j + 1] else j
        // Where each character occurs in the new text, in increasing order.
        val occurrences = HashMap<Char, IntArray>().apply {
            val counts = HashMap<Char, Int>()
            for (c in new) counts.merge(c, 1, Int::plus)
            for ((c, n) in counts) put(c, IntArray(n))
            val filled = HashMap<Char, Int>()
            for ((j, c) in new.withIndex()) {
                val k = filled.merge(c, 1, Int::plus)!! - 1
                getValue(c)[k] = j
            }
        }

        var reach = Intervals().apply { add(0, runEnd[0]) }
        for (i in old.indices) {
            val c = old[i]
            val next = Intervals()
            val at = occurrences[c]
            for (r in 0 until reach.size) {
                val a = reach.start(r)
                val b = reach.end(r)
                if (oldCovered[i]) {
                    // Skip old[i]: the interval stays; matching it can only add b + 1.
                    next.add(a, b)
                    if (b < m && new[b] == c) next.add(b + 1, runEnd[b + 1])
                } else if (at != null) {
                    // old[i] must match: every j in [a, b] with new[j] == c leads to j + 1. Occurrences inside the run
                    // just reached add nothing new, so jump past it.
                    var k = lowerBound(at, a, 0)
                    while (k < at.size && at[k] <= b && at[k] < m) {
                        val p = at[k] + 1
                        val e = runEnd[p]
                        next.add(p, e)
                        k = lowerBound(at, e, k + 1)
                    }
                }
            }
            if (next.size == 0) return false
            reach = next
        }
        return reach.end(reach.size - 1) == m
    }

    /** The first index from [from] whose value is at least [value]; [values] is increasing. */
    private fun lowerBound(values: IntArray, value: Int, from: Int): Int {
        var low = from
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < value) low = mid + 1 else high = mid
        }
        return low
    }

    /** Disjoint closed intervals of offsets, added in increasing order of start; overlapping or adjacent ones merge. */
    private class Intervals {
        private var starts = IntArray(4)
        private var ends = IntArray(4)
        var size = 0
            private set

        fun start(i: Int) = starts[i]

        fun end(i: Int) = ends[i]

        fun add(start: Int, end: Int) {
            if (size > 0 && start <= ends[size - 1] + 1) {
                if (end > ends[size - 1]) ends[size - 1] = end
                return
            }
            if (size == starts.size) {
                starts = starts.copyOf(size * 2)
                ends = ends.copyOf(size * 2)
            }
            starts[size] = start
            ends[size] = end
            size++
        }
    }

    /** Points at the first position where the uncovered parts of the two sides disagree; a hint for the failure message. */
    private fun describe(old: CharSequence, oldCovered: BooleanArray, new: CharSequence, newCovered: BooleanArray): String {
        val oldUncovered = old.indices.filterNot { oldCovered[it] }
        val newUncovered = new.indices.filterNot { newCovered[it] }
        val k = oldUncovered.indices.firstOrNull { it >= newUncovered.size || old[oldUncovered[it]] != new[newUncovered[it]] }
            ?: newUncovered.size.coerceAtMost(oldUncovered.size)
        val oldAt = oldUncovered.getOrNull(k)
        val newAt = newUncovered.getOrNull(k)
        return "a difference is not covered by any reported change: uncovered text first disagrees at " +
            "old offset ${oldAt ?: old.length} (${context(old, oldAt)}) and new offset ${newAt ?: new.length} (${context(new, newAt)})"
    }

    private fun context(text: CharSequence, at: Int?): String =
        if (at == null) "end of text" else "\"" + text.subSequence(maxOf(0, at - 15), minOf(text.length, at + 15)).toString()
            .replace("\n", "\\n") + "\""
}
