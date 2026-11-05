package io.github.agadek.smartdiff.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The minimised outline (outline/02 R8 to R11): a thin strip at the diff's right edge with the outline's icon. Clicking
 * it restores the panel at the splitter's remembered width without comparing again. The summary line, when there is
 * one, is the strip's tooltip and accessible name.
 */
internal class OutlineStrip(private val restore: () -> Unit) : JPanel(BorderLayout()) {

    internal val restoreButton = JButton(AllIcons.Toolwindows.ToolWindowStructure)

    init {
        name = "smartdiff.outline.strip"
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(WIDTH), 0)
        minimumSize = preferredSize
        maximumSize = Dimension(JBUI.scale(WIDTH), Int.MAX_VALUE)

        restoreButton.apply {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            verticalAlignment = SwingConstants.TOP
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(2)
            addActionListener { restore() }
        }
        // The button fills the strip, so a click anywhere on it restores the panel, not only on the icon.
        add(restoreButton, BorderLayout.CENTER)
        show(null)
    }

    /** Shows [summary] in the tooltip and accessible name; null while the outline has no result yet. */
    internal fun show(summary: String?) {
        val text = if (summary.isNullOrBlank()) "Structural outline, minimised" else "Structural outline, minimised: $summary"
        toolTipText = text
        accessibleContext?.accessibleName = text
        restoreButton.toolTipText = text
        restoreButton.accessibleContext.accessibleName = "$text. Click to restore."
    }

    private companion object {
        /** Thin enough to leave nearly all of the diff to the editors, wide enough for the icon (outline/02 R8). */
        const val WIDTH = 24
    }
}
