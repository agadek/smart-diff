package io.github.agadek.smartdiff.ui

import com.intellij.openapi.util.Key

/** The part of an outline controller that the toolbar toggle uses, for two- and three-sided diffs. */
internal interface OutlineToggleTarget {
    val isOutlineVisible: Boolean

    fun setOutlineVisible(visible: Boolean)
}

/** The toolbar toggle's state for one diff window, shared by the viewers it shows one after another. */
internal object OutlineVisibility {
    val VISIBLE: Key<Boolean> = Key.create("smartdiff.outline.visible")
}
