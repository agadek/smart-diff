package io.github.agadek.smartdiff.ui.merge

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/** The rounded frame the merge outline draws around a selected declaration, in a three-sided diff or the merge window. */
internal object MergeOutlineFrame : CustomHighlighterRenderer {
    val COLOR: Color = JBColor(Color(0xE0, 0x7B, 0x00), Color(0xF2, 0xA0, 0x3D))

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        if (!highlighter.isValid) return
        val document = editor.document
        val first = document.getLineNumber(highlighter.startOffset)
        val last = document.getLineNumber(maxOf(highlighter.startOffset, highlighter.endOffset - 1))
        val top = editor.logicalPositionToXY(LogicalPosition(first, 0)).y
        val bottom = editor.logicalPositionToXY(LogicalPosition(last, 0)).y + editor.lineHeight
        val area = editor.scrollingModel.visibleArea
        val inset = JBUIScale.scale(2)
        val arc = JBUIScale.scale(8)
        val x = area.x + inset
        val width = area.width - 2 * inset - 1
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = ColorUtil.withAlpha(COLOR, 0.12)
            g2.fillRoundRect(x, top, width, bottom - top - 1, arc, arc)
            g2.color = COLOR
            g2.stroke = BasicStroke(JBUIScale.scale(2f))
            g2.drawRoundRect(x, top, width, bottom - top - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }
}
