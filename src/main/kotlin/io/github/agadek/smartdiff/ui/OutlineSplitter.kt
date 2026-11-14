package io.github.agadek.smartdiff.ui

import com.intellij.diff.tools.util.base.DiffPanelBase
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
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

    val splitter = OnePixelSplitter(false, proportionKey, DEFAULT_PROPORTION)
    // Load the remembered width now, not only in addNotify, so a new diff starts at the last width in every host.
    splitter.proportion = PropertiesComponent.getInstance().getFloat(proportionKey, DEFAULT_PROPORTION)
    splitter.firstComponent = editors
    panel.remove(editors)
    panel.add(splitter, BorderLayout.CENTER)
    return splitter
}

/** The share of the diff width the editors get when no width was remembered; the outline gets the rest. */
internal const val DEFAULT_PROPORTION = 0.75f

/** In unscaled pixels; [outlineMinimumWidth] applies the IDE's scale and font size. */
private const val MINIMUM_WIDTH = 200

/**
 * The narrowest an outline panel may get, in scaled pixels: wide enough for the header actions and the first words of a
 * row, narrow enough to leave the editors usable. The panel reports it as its minimum, so the splitter stops dragging
 * there (outline/09 R1, R4).
 */
internal fun outlineMinimumWidth(): Int = JBUI.scale(MINIMUM_WIDTH)

/** Puts the editors back when the outline cannot attach after [insertOutlineSplitter] changed the viewer. */
internal fun removeOutlineSplitter(splitter: OnePixelSplitter) {
    val panel = splitter.parent as? DiffPanelBase ?: return
    val editors = splitter.firstComponent ?: return
    splitter.secondComponent = null
    splitter.firstComponent = null
    panel.remove(splitter)
    panel.add(editors, BorderLayout.CENTER)
    panel.revalidate()
    panel.repaint()
}
