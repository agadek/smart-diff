package io.github.agadek.smartdiff.ui

import com.intellij.openapi.util.Key

/**
 * An outline row named by its own title and the titles of the rows above it, from the top level down, so it can be
 * found again in another outline of the same file (technical specification §9.4). A group is named by its kind, since
 * its title counts its rows and the change type filter may hide some of them.
 */
class OutlinePath(val steps: List<Step>) {

    class Step(val kind: ItemKind, val title: String) {
        fun matches(item: OutlineItem): Boolean = item.kind == kind && (item.isGroup || item.title == title)
    }

    /**
     * The rows from the top level down to this path's row, among [items]; shorter when a row is not there, for example
     * because the filter hides it, and empty when not even the top-level row is.
     */
    fun find(items: List<OutlineItem>): List<OutlineItem> {
        val found = mutableListOf<OutlineItem>()
        var level = items
        for (step in steps) {
            val item = level.firstOrNull(step::matches) ?: break
            found += item
            level = item.children
        }
        return found
    }

    override fun toString(): String = steps.joinToString(" / ") { it.title }

    companion object {
        /**
         * A row to select once the outline of a diff request is shown, put on the request by the Changes tree when the
         * user opens a declaration there.
         */
        val KEY: Key<OutlinePath> = Key.create("smartdiff.outline.path")

        fun of(items: List<OutlineItem>): OutlinePath = OutlinePath(items.map { Step(it.kind, it.title) })
    }
}
