package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind

/**
 * One declaration found in the PSI, with the parts every PSI provider needs: its source range, its members, the
 * whitespace before it and whether a parse error lies in its own source (technical specification §6.1, §6.7).
 */
internal interface PsiDeclarationNode {
    val range: TextRange
    val children: List<PsiDeclarationNode>

    /** The whitespace between the previous sibling (or the container's start) and this declaration. */
    var leading: TextRange?

    /** Whether a parse error lies in [range] but outside the children's extents. */
    var parseError: Boolean

    /** The range with the leading whitespace: the source its container does not own. */
    val extent: TextRange get() = leading?.let { TextRange(it.start, range.end) } ?: range
}

/**
 * The parts of [PsiDeclarationProvider.build] that a provider with its own PSI walk reuses (languages/06): leading
 * whitespace, parse errors and the source outside a set of ranges. Keeping them here means every PSI-backed provider
 * assigns the same whitespace and attributes a parse error to the same declaration.
 */
internal object PsiTreeParts {

    /**
     * Each declaration takes the layout run between its previous sibling (or its container's start) and itself. A
     * provider whose lexer emits a line break as its own token can widen [isLayout].
     */
    fun assignLeading(
        roots: List<PsiDeclarationNode>,
        tokens: LexedTokens,
        containerStart: Int = 0,
        isLayout: (Token) -> Boolean = { it.kind == TokenKind.WHITESPACE },
    ) {
        var previousEnd = containerStart
        for (node in roots) {
            val start = tokens.layoutStart(node.range.start, notBefore = previousEnd, isLayout)
            if (start < node.range.start) node.leading = TextRange(start, node.range.start)
            assignLeading(node.children, tokens, node.range.start, isLayout)
            previousEnd = node.range.end
        }
    }

    /**
     * Marks the innermost declaration containing each parse error and returns true when an error lies outside every
     * declaration, so it belongs to the file region (§6.7). [isParseError] lets a language ignore markup errors.
     */
    fun markParseErrors(
        file: PsiFile,
        roots: List<PsiDeclarationNode>,
        cancellation: Cancellation,
        isParseError: (PsiErrorElement) -> Boolean = { true },
    ): Boolean {
        var fileRegionError = false
        for (error in PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)) {
            cancellation.checkCanceled()
            if (!isParseError(error)) continue
            val owner = innermost(roots, error.textRange.startOffset)
            if (owner == null) fileRegionError = true else owner.parseError = true
        }
        return fileRegionError
    }

    private fun innermost(nodes: List<PsiDeclarationNode>, offset: Int): PsiDeclarationNode? {
        for (node in nodes) {
            if (offset < node.range.start || offset > node.range.end) continue
            return innermost(node.children, offset) ?: node
        }
        return null
    }

    /** The non-empty parts of [range] outside [excluded], which are sorted and inside [range]. */
    fun gaps(range: TextRange, excluded: List<TextRange>): List<TextRange> {
        val gaps = mutableListOf<TextRange>()
        var start = range.start
        for (part in excluded) {
            if (part.start > start) gaps += TextRange(start, part.start)
            start = maxOf(start, part.end)
        }
        if (range.end > start) gaps += TextRange(start, range.end)
        return gaps
    }
}
