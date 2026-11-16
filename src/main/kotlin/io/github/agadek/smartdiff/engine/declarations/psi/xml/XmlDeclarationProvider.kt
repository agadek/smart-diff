package io.github.agadek.smartdiff.engine.declarations.psi.xml

import com.intellij.lang.Language
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlDoctype
import com.intellij.psi.xml.XmlElementType
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlProcessingInstruction
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlTokenType
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider

/**
 * XML declarations (languages/13-xml): each tag is a `TAG` declaration whose child tags are its members. A tag is named
 * by its Maven coordinates (`dependency` and `plugin`), its `id` or `name` attribute, or its tag name. Attributes, text,
 * comments, CDATA and processing instructions are content of their tag; the prolog is the file region.
 */
class XmlDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INSIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == XMLLanguage.INSTANCE.id

    /**
     * Only the XML lexer's whitespace token types are layout (R10). A blank token of another type is content: for
     * example a blank attribute value or the characters of a comment (R7, R14). Processing instructions and DOCTYPEs
     * use the same generic whitespace type as the layout between attributes, so [significantWhitespace] keeps their
     * spacing exact (R15).
     */
    override fun tokenKind(type: String, text: String, kind: TokenKind): TokenKind =
        if (kind == TokenKind.OTHER && text.isNotEmpty() && text.isBlank() && type in LAYOUT_TOKEN_TYPES) TokenKind.WHITESPACE else kind

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText(fileName ?: DEFAULT_NAME, XMLLanguage.INSTANCE, text, false, false)

    override fun topLevel(file: PsiFile): List<PsiElement> = listOfNotNull((file as? XmlFile)?.rootTag)

    override fun describe(element: PsiElement): DeclarationInfo? {
        val tag = element as? XmlTag ?: return null
        val tagName = tag.name
        if (tagName.isEmpty()) return null
        val members = tag.subTags.toList()
        coordinates(tag)?.let { return DeclarationInfo(DeclarationKind.TAG, "$tagName $it", signature = null, members = members) }
        for (attribute in IDENTIFYING_ATTRIBUTES) {
            val value = tag.getAttribute(attribute)?.valueElement ?: continue
            val text = value.value
            if (text.isEmpty()) continue
            val range = value.valueTextRange
            return DeclarationInfo(
                DeclarationKind.TAG, "$tagName $attribute=$text", signature = null, members = members,
                nameRange = TextRange(range.startOffset, range.endOffset),
            )
        }
        return DeclarationInfo(DeclarationKind.TAG, tagName, signature = null, members = members)
    }

    /** `groupId:artifactId` of a Maven `dependency` or `plugin` tag, or null when the tag has no such coordinates. */
    private fun coordinates(tag: XmlTag): String? {
        if (tag.name != "dependency" && tag.name != "plugin") return null
        val groupId = childText(tag, "groupId") ?: return null
        val artifactId = childText(tag, "artifactId") ?: return null
        return "$groupId:$artifactId"
    }

    private fun childText(tag: XmlTag, name: String): String? =
        tag.subTags.firstOrNull { it.name == name }?.value?.text?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Ranges whose spacing compares exactly: text content, CDATA, processing instructions and DOCTYPEs (R9, R14, R15).
     * A text's range spans its non-whitespace core and any edge whitespace without a line break, so spaces beside an
     * inline child tag or an entity reference stay exact while indentation around it stays layout (R10).
     */
    override fun significantWhitespace(file: PsiFile): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        for (text in PsiTreeUtil.findChildrenOfType(file, XmlText::class.java)) {
            val content = text.text
            val first = content.indexOfFirst { !it.isWhitespace() }
            if (first < 0) continue
            val last = content.indexOfLast { !it.isWhitespace() }
            val start = text.textRange.startOffset
            val from = if (content.substring(0, first).hasLineBreak()) first else 0
            val to = if (content.substring(last + 1).hasLineBreak()) last + 1 else content.length
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
        return ranges
    }

    private fun String.hasLineBreak() = any { it == '\n' || it == '\r' }

    private companion object {
        const val DEFAULT_NAME = "SmartDiff.xml"
        val IDENTIFYING_ATTRIBUTES = listOf("id", "name")
        val LAYOUT_TOKEN_TYPES = setOf(
            XmlTokenType.XML_WHITE_SPACE.toString(),
            XmlTokenType.XML_REAL_WHITE_SPACE.toString(),
            TokenType.WHITE_SPACE.toString(),
        )
    }
}
