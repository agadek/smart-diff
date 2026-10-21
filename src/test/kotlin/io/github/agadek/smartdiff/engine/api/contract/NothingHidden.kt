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
     * side. Covered characters may be matched or left unmatched. Dynamic programming over suffixes, one row at a time.
     */
    private fun alignable(old: CharSequence, oldCovered: BooleanArray, new: CharSequence, newCovered: BooleanArray): Boolean {
        val m = new.length
        var below = BooleanArray(m + 1) // row i + 1
        below[m] = true
        for (j in m - 1 downTo 0) below[j] = newCovered[j] && below[j + 1]
        for (i in old.length - 1 downTo 0) {
            val row = BooleanArray(m + 1)
            row[m] = oldCovered[i] && below[m]
            for (j in m - 1 downTo 0) {
                row[j] = (oldCovered[i] && below[j]) ||
                    (newCovered[j] && row[j + 1]) ||
                    (old[i] == new[j] && below[j + 1])
            }
            below = row
        }
        return below[0]
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
