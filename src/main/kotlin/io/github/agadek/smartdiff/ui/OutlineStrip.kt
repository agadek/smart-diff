package io.github.agadek.smartdiff.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.border.Border

/**
 * The minimised outline (outline/02 R8 to R11): a thin strip at the diff's right edge with the outline's icon. Clicking
 * it, or pressing Enter or Space while it has focus, restores the panel at the splitter's remembered width without
 * comparing again. The summary line or fallback reason, when there is one, is the strip's tooltip and accessible name.
 */
internal class OutlineStrip(private val restore: () -> Unit) : JPanel(BorderLayout()) {

    internal val restoreButton = JButton(AllIcons.Toolwindows.ToolWindowStructure)

    private val idleBorder: Border = JBUI.Borders.empty(1)
    private val focusBorder: Border = JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 1)

    init {
        name = "smartdiff.outline.strip"
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(WIDTH), 0)
        minimumSize = preferredSize
        maximumSize = Dimension(JBUI.scale(WIDTH), Int.MAX_VALUE)

        restoreButton.apply {
            isContentAreaFilled = false
            isBorderPainted = true
            isFocusPainted = false
            border = idleBorder
            verticalAlignment = SwingConstants.TOP
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(2)
            addActionListener { restore() }
            // JButton binds Space but not Enter; Enter is the usual confirm key for a focused button.
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), RESTORE)
            actionMap.put(RESTORE, object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) = restoreButton.doClick()
            })
            addFocusListener(object : FocusAdapter() {
                override fun focusGained(event: FocusEvent) {
                    border = focusBorder
                }

                override fun focusLost(event: FocusEvent) {
                    border = idleBorder
                }
            })
        }
        // The button fills the strip, so a click anywhere on it restores the panel, not only on the icon.
        add(restoreButton, BorderLayout.CENTER)
        show(null)
    }

    /** Shows [detail] in the tooltip and accessible name; null before any result or fallback message. */
    internal fun show(detail: String?) {
        val text = if (detail.isNullOrBlank()) "Structural outline, minimised" else "Structural outline, minimised: $detail"
        toolTipText = text
        accessibleContext?.accessibleName = text
        restoreButton.toolTipText = text
        restoreButton.accessibleContext.accessibleName = "$text. Click or press Enter to restore."
    }

    private companion object {
        /** Thin enough to leave nearly all of the diff to the editors, wide enough for the icon (outline/02 R8). */
        const val WIDTH = 24

        const val RESTORE = "smartdiff.outline.restore"
    }
}
