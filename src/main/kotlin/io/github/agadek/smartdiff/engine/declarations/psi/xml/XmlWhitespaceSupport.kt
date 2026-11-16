package io.github.agadek.smartdiff.engine.declarations.psi.xml

import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlDoctype
import com.intellij.psi.xml.XmlElementType
import com.intellij.psi.xml.XmlProcessingInstruction
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlTokenType
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind

/**
 * Whitespace rules the XML and HTML providers share (languages/13 details §6, languages/15 details §6). HTML adds its
 * own exact ranges on top of these; the XML provider uses them unchanged, so its results stay the same (R21).
 */
internal object XmlWhitespaceSupport {

    /** Only the XML lexer's whitespace token types and the platform's generic whitespace are layout (R10). */
    private val LAYOUT_TOKEN_TYPES = setOf(
        XmlTokenType.XML_WHITE_SPACE.toString(),
        XmlTokenType.XML_REAL_WHITE_SPACE.toString(),
        TokenType.WHITE_SPACE.toString(),
    )

    fun tokenKind(type: String, text: String, kind: TokenKind): TokenKind =
        if (kind == TokenKind.OTHER && text.isNotEmpty() && text.isBlank() && type in LAYOUT_TOKEN_TYPES) TokenKind.WHITESPACE else kind

    fun hasLineBreak(text: String): Boolean = text.any { it == '\n' || it == '\r' }

    /**
     * Adds the ranges whose spacing compares exactly for any XML-family file: each non-blank [XmlText] with its
     * line-break-free edge whitespace, and each CDATA section, processing instruction and DOCTYPE, whole (R9, R14,
     * R15, R16).
     */
    fun addXmlRanges(file: PsiFile, ranges: MutableList<TextRange>) {
        for (text in PsiTreeUtil.findChildrenOfType(file, XmlText::class.java)) {
            val content = text.text
            val first = content.indexOfFirst { !it.isWhitespace() }
            if (first < 0) continue
            val last = content.indexOfLast { !it.isWhitespace() }
            val start = text.textRange.startOffset
            val from = if (hasLineBreak(content.substring(0, first))) first else 0
            val to = if (hasLineBreak(content.substring(last + 1))) last + 1 else content.length
            ranges += TextRange(start + from, start + to)
        }
        PsiTreeUtil.processElements(file) { element ->
            if (element.node.elementType == XmlElementType.XML_CDATA ||
                element is XmlProcessingInstruction || element is XmlDoctype
            ) {
                ranges += TextRange(element.textRange.startOffset, element.textRange.endOffset)
            }
            true
        }
    }

    /** The content of [tag], from the end of its start tag to the start of its end tag. */
    fun contentRange(tag: XmlTag): TextRange {
        val whole = tag.textRange
        var start = whole.endOffset
        var end = whole.endOffset
        for (child in tag.children) {
            when (child.node.elementType) {
                XmlTokenType.XML_TAG_END -> start = child.textRange.endOffset
                XmlTokenType.XML_END_TAG_START -> end = child.textRange.startOffset
            }
        }
        return TextRange(start, maxOf(end, start))
    }
}
