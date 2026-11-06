package io.github.agadek.smartdiff.ui.changes

import io.github.agadek.smartdiff.ui.ChangeType
import io.github.agadek.smartdiff.ui.OutlineItem

/** The declaration rows left after the Changes tree's filter, and how many changes it hid. */
class FilteredRows(val items: List<OutlineItem>, val hiddenCount: Int)

/**
 * The Changes tree's own change type filter, applied to the cached outline rows without comparing a file again
 * (changes-tree/04). It follows the outline's rules (outline/03 R2 to R5): a change is hidden only when every one of
 * its labels is of a hidden type, a hidden change with shown children stays as a container without labels, and
 * uncertain, rename candidate and unanalyzed rows are never hidden.
 */
object ChangesTreeFilter {

    /** The rows of [items] that stay, and the number of changes hidden, including hidden containers that stay. */
    fun filter(items: List<OutlineItem>, hidden: Set<ChangeType>): FilteredRows {
        if (hidden.isEmpty()) return FilteredRows(items, 0)
        val hiddenTags = hidden.flatMapTo(HashSet()) { it.tags }
        var hiddenCount = 0

        fun filter(item: OutlineItem): OutlineItem? {
            val children = item.children.mapNotNull(::filter)
            val isHidden = item.tags.isNotEmpty() && item.tags.all { it in hiddenTags }
            if (isHidden) {
                hiddenCount++
                if (children.isEmpty()) return null
                return OutlineItem(
                    item.kind,
                    item.title,
                    emptyList(),
                    item.oldRange,
                    item.newRange,
                    item.details,
                    children,
                )
            }
            // A container with no labels of its own disappears when every child is filtered. A shown change with
            // its own labels stays even when all of its children are hidden.
            if (item.tags.isEmpty() && item.children.isNotEmpty() && children.isEmpty()) return null
            if (children == item.children) return item
            return OutlineItem(
                item.kind,
                item.title,
                item.tags,
                item.oldRange,
                item.newRange,
                item.details,
                children,
                item.note,
            )
        }

        return FilteredRows(items.mapNotNull(::filter), hiddenCount)
    }

    /** [text] with how many changes the filter hid, or `· all hidden` when no declaration row is left. */
    fun summary(text: String, filtered: FilteredRows): String = when {
        filtered.hiddenCount == 0 -> text
        filtered.items.isEmpty() -> "$text · all hidden"
        else -> "$text · ${filtered.hiddenCount} hidden"
    }
}
