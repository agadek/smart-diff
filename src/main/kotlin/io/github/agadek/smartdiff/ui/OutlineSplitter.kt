package io.github.agadek.smartdiff.ui

import com.intellij.diff.tools.util.base.DiffPanelBase
import com.intellij.ui.OnePixelSplitter
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * Moves the editors of a native diff or merge viewer into a splitter, leaving the second component for an outline
 * (ADR 0005, ADR 0007).
 *
 * The viewer's component is a [DiffPanelBase] whose `BorderLayout` centre holds the editors; that centre becomes the
 * splitter's first component. Returns null and changes nothing when the component has another layout, so the native
 * viewer shows as usual.
 */
internal fun insertOutlineSplitter(component: JComponent, proportionKey: String): OnePixelSplitter? {
    val panel = component as? DiffPanelBase ?: return null
    val layout = panel.layout as? BorderLayout ?: return null
    val editors = layout.getLayoutComponent(BorderLayout.CENTER) as? JComponent ?: return null

    val splitter = OnePixelSplitter(false, proportionKey, 0.75f)
    splitter.firstComponent = editors
    panel.remove(editors)
    panel.add(splitter, BorderLayout.CENTER)
    return splitter
}
