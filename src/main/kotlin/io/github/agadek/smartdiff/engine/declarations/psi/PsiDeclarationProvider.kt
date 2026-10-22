package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.ParserDefinition
import com.intellij.lexer.Lexer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy

/** What a provider says about a PSI element that is a declaration (technical specification §6.4, §6.5). */
class DeclarationInfo(
    val kind: DeclarationKind,
    val name: String,
    /** Normalized parameter types (§6.6); null for declarations that take no parameters. */
    val signature: String?,
    /** Elements that may be nested declarations, in source order. */
    val members: List<PsiElement> = emptyList(),
    /** The parameter types for labels ([Signatures.label]); for a Kotlin extension, without the receiver. */
    val displaySignature: String? = signature,
    /** The receiver type of a Kotlin extension, for labels. */
    val receiver: String? = null,
)

/**
 * A provider that finds declarations in the PSI and takes their tokens from the language's lexer. Subclasses say which
 * elements are declarations; this class builds the tree: ranges from the PSI, own tokens sliced from one lexer pass
 * (§6.3), and parse errors given to the innermost declaration that contains them (§6.7).
 */
abstract class PsiDeclarationProvider(private val whitespacePolicy: WhitespacePolicy) : DeclarationProvider {

    /** Elements of [file] that may be top-level declarations, in source order. */
    protected abstract fun topLevel(file: PsiFile): List<PsiElement>

    /**
     * Describes [element], or returns null when it is not a declaration or its name was not parsed. The source of an
     * element without a description, members included, belongs to its container.
     */
    protected abstract fun describe(element: PsiElement): DeclarationInfo?

    /** The lexer for the tokens: the one of the file language's parser definition. */
    private fun createLexer(file: PsiFile): Lexer = parserDefinition(file).createLexer(file.project)

    override fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree {
        val text = file.viewProvider.contents
        val nodes = HashMap<PsiElement, Node>()

        fun node(element: PsiElement): Node? {
            cancellation.checkCanceled()
            val info = describe(element) ?: return null
            val range = element.textRange
            if (range.isEmpty) return null
            return Node(info, TextRange(range.startOffset, range.endOffset), info.members.mapNotNull(::node)).also { nodes[element] = it }
        }
        val topLevel = topLevel(file).mapNotNull(::node)

        var fileRegionError = false
        for (error in PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)) {
            cancellation.checkCanceled()
            val owner = generateSequence(error.parent) { it.parent }.takeWhile { it !is PsiFile }.firstNotNullOfOrNull { nodes[it] }
            if (owner == null) fileRegionError = true else owner.parseError = true
        }

        val tokens = LexedTokens.lex(createLexer(file), parserDefinition(file), text, cancellation)
        // Each declaration takes the whitespace between its previous sibling (or its container's start) and itself.
        fun assignLeading(siblings: List<Node>, containerStart: Int) {
            var previousEnd = containerStart
            for (node in siblings) {
                val start = tokens.whitespaceStart(node.range.start, notBefore = previousEnd)
                if (start < node.range.start) node.leading = TextRange(start, node.range.start)
                assignLeading(node.children, node.range.start)
                previousEnd = node.range.end
            }
        }
        assignLeading(topLevel, 0)

        fun declaration(node: Node): Declaration {
            cancellation.checkCanceled()
            val leading = node.leading?.let { tokens.own(it, emptyList()) }.orEmpty()
            return Declaration(
                kind = node.info.kind,
                name = node.info.name,
                signature = node.info.signature,
                range = node.range,
                children = node.children.map(::declaration),
                ownTokens = leading + tokens.own(node.range, node.children.map { it.extent }),
                hasParseError = node.parseError,
                leadingWhitespace = node.leading,
                displaySignature = node.info.displaySignature,
                receiver = node.info.receiver,
            )
        }
        val whole = TextRange(0, text.length)
        val fileRegion = FileRegion(
            ranges = gaps(whole, topLevel.map { it.extent }),
            tokens = tokens.own(whole, topLevel.map { it.extent }),
            hasParseError = fileRegionError,
        )
        return DeclarationTree(topLevel.map(::declaration), fileRegion, whitespacePolicy)
    }

    private class Node(val info: DeclarationInfo, val range: TextRange, val children: List<Node>) {
        var parseError = false
        var leading: TextRange? = null

        /** The range with the leading whitespace: the source its container does not own. */
        val extent: TextRange get() = leading?.let { TextRange(it.start, range.end) } ?: range
    }

    /** The non-empty parts of [range] outside [excluded], which are sorted and inside [range]. */
    private fun gaps(range: TextRange, excluded: List<TextRange>): List<TextRange> {
        val gaps = mutableListOf<TextRange>()
        var start = range.start
        for (part in excluded) {
            if (part.start > start) gaps += TextRange(start, part.start)
            start = maxOf(start, part.end)
        }
        if (range.end > start) gaps += TextRange(start, range.end)
        return gaps
    }

    protected fun parserDefinition(file: PsiFile): ParserDefinition =
        LanguageParserDefinitions.INSTANCE.forLanguage(file.language)
            ?: error("no parser definition for ${file.language.id}")
}
