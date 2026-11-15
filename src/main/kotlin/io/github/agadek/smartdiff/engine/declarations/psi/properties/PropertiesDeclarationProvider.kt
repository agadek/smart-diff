package io.github.agadek.smartdiff.engine.declarations.psi.properties

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.properties.IProperty
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider

/**
 * Java properties declarations (languages/11-properties): each property is a top-level [DeclarationKind.KEY] named by
 * its unescaped key, flat and in source order.
 */
class PropertiesDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INSIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == "Properties"

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val name = fileName ?: DEFAULT_NAME
        val language = Language.findLanguageByID("Properties") ?: error("Properties language is not available")
        return PsiFileFactory.getInstance(project).createFileFromText(name, language, text, false, false)
    }

    override fun topLevel(file: PsiFile): List<PsiElement> {
        val propertiesFile = file as? PropertiesFile ?: return emptyList()
        val byIProperty = try {
            propertiesFile.properties.mapNotNull { it.psiElement }
        } catch (_: Throwable) {
            emptyList()
        }
        if (byIProperty.isNotEmpty()) return byIProperty
        return file.children.filter { child ->
            child is Property || child is IProperty || child.javaClass.simpleName.endsWith("Property")
        }
    }

    override fun describe(element: PsiElement): DeclarationInfo? {
        val iProperty: IProperty = when {
            element is IProperty -> element
            element is Property -> element
            else -> element as? IProperty ?: return null
        }
        val name = iProperty.unescapedKey ?: iProperty.key ?: return null
        val psi = iProperty.psiElement ?: element
        val nameRange = try {
            psi.children.firstOrNull()?.textRange
        } catch (_: Throwable) {
            null
        } ?: psi.textRange
        return DeclarationInfo(
            kind = DeclarationKind.KEY,
            name = name,
            signature = null,
            members = emptyList(),
            nameRange = nameRange?.let { TextRange(it.startOffset, it.endOffset) },
        )
    }

    override fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree {
        val text = file.viewProvider.contents
        val elements = topLevel(file).mapNotNull { element ->
            cancellation.checkCanceled()
            val info = describe(element) ?: return@mapNotNull null
            val range = element.textRange.let { TextRange(it.startOffset, it.endOffset) }
            if (range.length == 0) null else info to range
        }

        val definition = LanguageParserDefinitions.INSTANCE.forLanguage(file.language)
            ?: error("no parser definition for ${file.language.id}")
        val whitespace = definition.whitespaceTokens
        val comments = definition.commentTokens
        val starts = mutableListOf<Int>()
        val tokens = mutableListOf<Token>()
        val lexer = definition.createLexer(file.project)
        lexer.start(text)
        while (true) {
            val type = lexer.tokenType ?: break
            if (tokens.size % 1024 == 0) cancellation.checkCanceled()
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            val tokenText = text.subSequence(start, end).toString()
            val baseKind = when {
                whitespace.contains(type) -> TokenKind.WHITESPACE
                comments.contains(type) -> TokenKind.COMMENT
                else -> TokenKind.OTHER
            }
            val typeName = type.toString()
            if (baseKind == TokenKind.OTHER && tokenText.any { it == '=' || it == ':' } && tokenText.any(Char::isWhitespace)) {
                // Split separator tokens that include spacing so spacing around the separator is insignificant (R7).
                var i = 0
                while (i < tokenText.length) {
                    val c = tokenText[i]
                    if (c == '=' || c == ':') {
                        starts += start + i
                        tokens += Token(typeName, c.toString(), TokenKind.OTHER)
                        i++
                    } else {
                        var j = i + 1
                        while (j < tokenText.length && tokenText[j] != '=' && tokenText[j] != ':') j++
                        val part = tokenText.substring(i, j)
                        starts += start + i
                        tokens += Token(typeName, part, if (part.all(Char::isWhitespace)) TokenKind.WHITESPACE else TokenKind.OTHER)
                        i = j
                    }
                }
            } else {
                starts += start
                tokens += Token(typeName, tokenText, baseKind)
            }
            lexer.advance()
        }

        fun firstAtOrAfter(offset: Int): Int {
            var low = 0
            var high = starts.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (starts[mid] < offset) low = mid + 1 else high = mid
            }
            return low
        }

        fun own(range: TextRange, excluded: List<TextRange>): List<Token> {
            val own = mutableListOf<Token>()
            var next = 0
            var i = firstAtOrAfter(range.start)
            while (i < starts.size && starts[i] < range.end) {
                while (next < excluded.size && excluded[next].end <= starts[i]) next++
                val skip = next < excluded.size && starts[i] >= excluded[next].start
                if (skip) i = firstAtOrAfter(excluded[next].end) else own += tokens[i++]
            }
            return own
        }

        fun ownStarts(range: TextRange, excluded: List<TextRange>): List<Int> {
            val own = mutableListOf<Int>()
            var next = 0
            var i = firstAtOrAfter(range.start)
            while (i < starts.size && starts[i] < range.end) {
                while (next < excluded.size && excluded[next].end <= starts[i]) next++
                val skip = next < excluded.size && starts[i] >= excluded[next].start
                if (skip) i = firstAtOrAfter(excluded[next].end) else own += starts[i++]
            }
            return own
        }

        fun whitespaceStart(offset: Int, notBefore: Int): Int {
            val at = firstAtOrAfter(offset)
            if (at < starts.size && starts[at] != offset) return offset
            var i = at - 1
            while (i >= 0 && starts[i] >= notBefore && tokens[i].kind == TokenKind.WHITESPACE) i--
            return if (i + 1 < at) starts[i + 1] else offset
        }

        val declarations = mutableListOf<Declaration>()
        var previousEnd = 0
        val errors = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
        for ((info, range) in elements) {
            cancellation.checkCanceled()
            val leadingStart = whitespaceStart(range.start, previousEnd)
            val leading = if (leadingStart < range.start) TextRange(leadingStart, range.start) else null
            val extent = TextRange(leading?.start ?: range.start, range.end)
            val leadingTokens = leading?.let { own(it, emptyList()) }.orEmpty()
            val leadingTokenStarts = leading?.let { ownStarts(it, emptyList()) }.orEmpty()
            val hasError = errors.any { err ->
                val er = err.textRange
                er.startOffset >= range.start && er.endOffset <= range.end
            }
            declarations += Declaration(
                kind = info.kind,
                name = info.name,
                signature = info.signature,
                range = range,
                children = emptyList(),
                ownTokens = leadingTokens + own(range, emptyList()),
                hasParseError = hasError,
                leadingWhitespace = leading,
                displaySignature = info.displaySignature,
                receiver = info.receiver,
                ownTokenStarts = leadingTokenStarts + ownStarts(range, emptyList()),
                body = null,
                nameRange = info.nameRange,
                layout = null,
            )
            previousEnd = range.end
        }

        val whole = TextRange(0, text.length)
        val covered = declarations.map { TextRange(it.leadingWhitespace?.start ?: it.range.start, it.range.end) }
        fun gaps(container: TextRange, excluded: List<TextRange>): List<TextRange> {
            val gaps = mutableListOf<TextRange>()
            var start = container.start
            for (ex in excluded) {
                if (ex.start > start) gaps += TextRange(start, ex.start)
                start = ex.end
            }
            if (start < container.end) gaps += TextRange(start, container.end)
            return gaps
        }
        val fileRegion = FileRegion(
            ranges = gaps(whole, covered),
            tokens = own(whole, covered),
            hasParseError = errors.any { err ->
                val er = TextRange(err.textRange.startOffset, err.textRange.endOffset)
                covered.none { c -> er.start >= c.start && er.end <= c.end }
            },
        )
        return DeclarationTree(declarations, fileRegion, whitespacePolicy(file))
    }

    private companion object {
        const val DEFAULT_NAME = "SmartDiff.properties"
    }
}
