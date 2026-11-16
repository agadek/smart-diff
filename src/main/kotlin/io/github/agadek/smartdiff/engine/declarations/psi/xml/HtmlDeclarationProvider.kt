package io.github.agadek.smartdiff.engine.declarations.psi.xml

import com.intellij.lang.Language
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.xhtml.XHTMLLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider
import java.util.Locale

/**
 * HTML and XHTML declarations (languages/15): identified elements, `head`, `body`, `template`, `script` and `style`
 * elements are `TAG` declarations; every other element, its attributes and its text are content of the nearest
 * enclosing declaration or of the file region. Embedded JavaScript and CSS, and `pre` and `textarea` content, compare
 * exactly (R3, R4, R6, R9, R10).
 */
class HtmlDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INSIGNIFICANT) {

    /** Exactly HTML and XHTML; template languages with their own ids are left out (R2, R21). */
    override fun supports(languageId: String): Boolean = languageId == "HTML" || languageId == "XHTML"

    /** The engine asks once for both sides; the id selects the language even when the file name does not (R2). */
    override fun dialect(project: Project, texts: List<CharSequence>, fileName: String?, languageId: String): Language? =
        if (languageId == "XHTML") XHTMLLanguage else HTMLLanguage.INSTANCE

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText(
            fileName ?: DEFAULT_NAME,
            dialect ?: if (fileName?.endsWith(".xhtml", ignoreCase = true) == true) XHTMLLanguage else HTMLLanguage.INSTANCE,
            text,
            false,
            false,
        )

    /** Declaration elements inside no declaration element, in document order (R7). */
    override fun topLevel(file: PsiFile): List<PsiElement> =
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).filter { tag ->
            isDeclarationElement(tag) && ancestors(tag).none { it is XmlTag && isDeclarationElement(it) }
        }

    override fun describe(element: PsiElement): DeclarationInfo? {
        val tag = element as? XmlTag ?: return null
        if (!isDeclarationElement(tag)) return null
        val name = tag.name.lowercase(Locale.ROOT)
        val members = members(tag)
        val id = attribute(tag, "id")
        val idValue = id?.value
        if (id != null && !idValue.isNullOrEmpty()) {
            return DeclarationInfo(
                DeclarationKind.TAG, "$name id=$idValue", signature = null, members = members, nameRange = valueRange(id),
            )
        }
        if (name == "script") {
            val src = attribute(tag, "src")
            val srcValue = src?.value
            if (src != null && !srcValue.isNullOrEmpty()) {
                return DeclarationInfo(
                    DeclarationKind.TAG, "script src=$srcValue", signature = null, members = members, nameRange = valueRange(src),
                )
            }
        }
        return DeclarationInfo(DeclarationKind.TAG, name, signature = null, members = members)
    }

    override fun tokenKind(type: String, text: String, kind: TokenKind): TokenKind =
        XmlWhitespaceSupport.tokenKind(type, text, kind)

    /** XML text ranges plus the exact ranges of embedded code and rendered whitespace (R9, R10). */
    override fun significantWhitespace(file: PsiFile): List<TextRange> = buildList {
        XmlWhitespaceSupport.addXmlRanges(file, this)
        val text = file.text
        for (tag in PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)) {
            val content = XmlWhitespaceSupport.contentRange(tag)
            when (tag.name.lowercase(Locale.ROOT)) {
                "script", "style" -> {
                    val inner = text.substring(content.start, content.end)
                    val first = inner.indexOfFirst { !it.isWhitespace() }
                    if (first >= 0) {
                        val last = inner.indexOfLast { !it.isWhitespace() }
                        val from = if (XmlWhitespaceSupport.hasLineBreak(inner.substring(0, first))) first else 0
                        val to = if (XmlWhitespaceSupport.hasLineBreak(inner.substring(last + 1))) last + 1 else inner.length
                        add(TextRange(content.start + from, content.start + to))
                    }
                }
                "pre", "textarea" -> add(content)
            }
        }
    }

    /** An `id` attribute matches without regard to case; an empty or valueless one does not name the element. */
    private fun isDeclarationElement(tag: XmlTag): Boolean {
        if (tag.name.isEmpty()) return false
        if (tag.name.lowercase(Locale.ROOT) in DECLARATION_TAGS) return true
        return attribute(tag, "id")?.value?.isNotEmpty() == true
    }

    /** Nearest declaration elements inside [tag], at any depth: stop at each one found (R7). */
    private fun members(tag: XmlTag): List<PsiElement> =
        PsiTreeUtil.findChildrenOfType(tag, XmlTag::class.java).filter { candidate ->
            isDeclarationElement(candidate) &&
                generateSequence(candidate.parent) { it.parent }
                    .takeWhile { it !== tag }
                    .none { it is XmlTag && isDeclarationElement(it) }
        }

    private fun ancestors(element: PsiElement): Sequence<PsiElement> =
        generateSequence(element.parent) { it.parent }

    private fun attribute(tag: XmlTag, name: String): XmlAttribute? =
        tag.attributes.firstOrNull { it.name.lowercase(Locale.ROOT) == name }

    private fun valueRange(attribute: XmlAttribute): TextRange? =
        attribute.valueElement?.valueTextRange?.let { TextRange(it.startOffset, it.endOffset) }

    private companion object {
        const val DEFAULT_NAME = "SmartDiff.html"
        val DECLARATION_TAGS = setOf("head", "body", "template", "script", "style")
    }
}
