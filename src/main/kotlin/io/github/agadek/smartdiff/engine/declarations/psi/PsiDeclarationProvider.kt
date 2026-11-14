package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.ParserDefinition
import com.intellij.lexer.Lexer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/** What a provider says about a PSI element that is a declaration (technical specification §6.4, §6.5, §6.8). */
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
    /**
     * For one of several declarations written together, such as `val a, b = 0`, the element they share; null when the
     * element is the declaration's own.
     */
    val group: PsiElement? = null,
    /** The declaration's part of [group]; null for the element's own range. */
    val range: TextRange? = null,
    /**
     * The body of a method, function or constructor (extraction detection specification §5.1): a block, whose inside
     * is the body, or an expression; null without a body.
     */
    val body: PsiElement? = null,
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

    /** Whether [error] is a parse error in code (§6.7); a language can leave out errors that are not, such as doc markup. */
    protected open fun isParseError(error: PsiErrorElement): Boolean = true

    /** How the whitespace of [file] counts (§8 step 4); the same for every file unless a dialect changes it. */
    protected open fun whitespacePolicy(file: PsiFile): WhitespacePolicy = whitespacePolicy

    /** The lexer for the tokens: the one of the file language's parser definition. */
    private fun createLexer(file: PsiFile): Lexer = parserDefinition(file).createLexer(file.project)

    override fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree {
        val text = file.viewProvider.contents

        fun node(element: PsiElement): Node? {
            cancellation.checkCanceled()
            val info = describe(element) ?: return null
            val range = info.range ?: element.textRange.let { TextRange(it.startOffset, it.endOffset) }
            if (range.length == 0) return null
            return Node(info, range, info.members.mapNotNull(::node))
        }
        val topLevel = topLevel(file).mapNotNull(::node)

        val fileRegionError = PsiTreeParts.markParseErrors(file, topLevel, cancellation, ::isParseError)

        val tokens = LexedTokens.lex(createLexer(file), parserDefinition(file), text, cancellation)
        PsiTreeParts.assignLeading(topLevel, tokens)

        fun declaration(node: Node): Declaration {
            cancellation.checkCanceled()
            val leading = node.leading?.let { tokens.own(it, emptyList()) }.orEmpty()
            val leadingStarts = node.leading?.let { tokens.ownStarts(it, emptyList()) }.orEmpty()
            val excluded = node.children.map { it.extent }
            return Declaration(
                kind = node.info.kind,
                name = node.info.name,
                signature = node.info.signature,
                range = node.range,
                children = node.children.map(::declaration),
                ownTokens = leading + tokens.own(node.range, excluded),
                hasParseError = node.parseError,
                leadingWhitespace = node.leading,
                displaySignature = node.info.displaySignature,
                receiver = node.info.receiver,
                ownTokenStarts = leadingStarts + tokens.ownStarts(node.range, excluded),
                body = node.info.body?.let(::body),
            )
        }
        val whole = TextRange(0, text.length)
        val fileRegion = FileRegion(
            ranges = PsiTreeParts.gaps(whole, topLevel.map { it.extent }),
            tokens = tokens.own(whole, topLevel.map { it.extent }),
            hasParseError = fileRegionError,
        )
        return DeclarationTree(topLevel.map(::declaration), fileRegion, whitespacePolicy(file))
    }

    private class Node(
        val info: DeclarationInfo,
        override val range: TextRange,
        override val children: List<Node>,
    ) : PsiDeclarationNode {
        override var parseError = false
        override var leading: TextRange? = null
    }

    /** The inside of a block's braces, or the whole of any other body. */
    private fun body(element: PsiElement): TextRange {
        val range = element.textRange
        val first = element.firstChild
        val last = element.lastChild
        return if (first != null && last != null && first !== last && first.text == "{" && last.text == "}") {
            TextRange(first.textRange.endOffset, last.textRange.startOffset)
        } else {
            TextRange(range.startOffset, range.endOffset)
        }
    }

    protected fun parserDefinition(file: PsiFile): ParserDefinition =
        LanguageParserDefinitions.INSTANCE.forLanguage(file.language)
            ?: error("no parser definition for ${file.language.id}")
}
