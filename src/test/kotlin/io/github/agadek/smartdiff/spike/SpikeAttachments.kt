package io.github.agadek.smartdiff.spike

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.tools.util.base.DiffPanelBase
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import javax.swing.JComponent

/** Spike S1 (technical specification §13): the two ways of attaching the outline, with a placeholder in place of the outline. */
internal fun placeholderOutline(): JComponent = JBLabel("SMART Diff outline (spike placeholder)").apply { name = "smartdiff.spike.outline" }

/**
 * Option A: put the outline beside the editors of a native side-by-side viewer, from a `DiffExtension`.
 *
 * The viewer's component is a [DiffPanelBase] with a `BorderLayout`; its centre holds the editors. The centre is moved into a
 * splitter with the outline as the second component, so the outline can be resized and hidden without touching the editors.
 */
internal object OptionA {

    /** Returns the outline's splitter, or null if [viewer] is not a native two-sided text viewer. */
    fun attach(viewer: FrameDiffTool.DiffViewer): OnePixelSplitter? {
        if (viewer !is TwosideTextDiffViewer) return null
        val panel = viewer.component as? DiffPanelBase ?: return null
        val layout = panel.layout as? BorderLayout ?: return null
        val center = layout.getLayoutComponent(BorderLayout.CENTER) as? JComponent ?: return null

        val splitter = OnePixelSplitter(false, 0.75f).apply {
            firstComponent = center
            secondComponent = placeholderOutline()
        }
        panel.remove(center)
        panel.add(splitter, BorderLayout.CENTER)
        return splitter
    }
}

/** Option B: an alternate viewer, registered as a `DiffTool`, that embeds the native side-by-side viewer and the outline. */
internal class OptionBDiffTool : FrameDiffTool {

    override fun getName(): String = "SMART Diff (spike)"

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean = SimpleDiffTool.INSTANCE.canShow(context, request)

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer =
        OptionBViewer(SimpleDiffTool.INSTANCE.createComponent(context, request))
}

internal class OptionBViewer(val native: FrameDiffTool.DiffViewer) : FrameDiffTool.DiffViewer {

    private val component = OnePixelSplitter(false, 0.75f).apply {
        firstComponent = native.component
        secondComponent = placeholderOutline()
    }

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent? = native.preferredFocusedComponent

    override fun init(): FrameDiffTool.ToolbarComponents = native.init()

    override fun dispose() = Disposer.dispose(native)
}
