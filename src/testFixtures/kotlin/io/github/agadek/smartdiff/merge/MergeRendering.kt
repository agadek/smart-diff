package io.github.agadek.smartdiff.merge

import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.ui.merge.MergeOutlineItem
import io.github.agadek.smartdiff.ui.merge.MergeOutlineLayout
import io.github.agadek.smartdiff.ui.merge.MergeOutlineModel
import io.github.agadek.smartdiff.ui.merge.MergeSideName

/**
 * The merge outline as one line per row (merge/05-scala-merges R10): its group, each side's labels and the lines the
 * row spans in the base, left and right. The container tree is the fullest rendering: it keeps every reported row,
 * including containers whose own group is unchanged.
 */
object MergeRendering {

    fun joined(joined: JoinedResult, base: String, left: String, right: String): String = buildString {
        appendLine("left: ${joined.left.status}  right: ${joined.right.status}")
        val model = MergeOutlineModel.build(
            joined, base, left, right,
            MergeSideName("left", "left"), MergeSideName("right", "right"),
            MergeOutlineLayout.CONTAINER_TREE,
        )
        fun visit(item: MergeOutlineItem, depth: Int) {
            val fields = mutableListOf(item.title, item.group?.name ?: item.kind.name)
            item.left?.let { fields += "left: ${it.value}" }
            item.right?.let { fields += "right: ${it.value}" }
            item.note?.let { fields += "note: $it" }
            fields += "base ${lines(item.baseRange, base)}"
            fields += "left ${lines(item.leftRange, left)}"
            fields += "right ${lines(item.rightRange, right)}"
            appendLine("  ".repeat(depth) + fields.joinToString("  "))
            item.children.forEach { visit(it, depth + 1) }
        }
        model.items.forEach { visit(it, 0) }
    }

    private fun lines(range: TextRange?, text: CharSequence): String {
        if (range == null) return "-"
        fun line(offset: Int) = 1 + (0 until offset).count { text[it] == '\n' }
        val first = line(range.start)
        val last = if (range.length == 0) first else line(range.end - 1)
        return if (first == last) "$first" else "$first-$last"
    }
}
