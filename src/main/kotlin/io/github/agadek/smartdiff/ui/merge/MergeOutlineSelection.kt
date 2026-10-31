package io.github.agadek.smartdiff.ui.merge

/** How a merge outline shows a selected row in the panes: a three-sided diff's three editors, or the merge window's result. */
internal interface MergeOutlineSelection {
    fun select(item: MergeOutlineItem?)

    fun navigate(item: MergeOutlineItem)

    fun clear()

    fun dispose() {}
}
