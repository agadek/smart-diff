package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.lang.ParserDefinition
import com.intellij.lexer.Lexer
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind

/**
 * The tokens of one lexer pass over a whole file, sliced by range (technical specification §6.3). A token belongs to
 * the range that contains its start, so a token crossing a range boundary is never split.
 */
internal class LexedTokens private constructor(private val starts: IntArray, private val tokens: List<Token>) {

    /** The tokens that start in [range] but in none of [excluded], which are sorted, disjoint and inside [range]. */
    fun own(range: TextRange, excluded: List<TextRange>): List<Token> = ownIndices(range, excluded).map { tokens[it] }

    /** The start offsets of the tokens [own] returns, in the same order. */
    fun ownStarts(range: TextRange, excluded: List<TextRange>): List<Int> = ownIndices(range, excluded).map { starts[it] }

    private fun ownIndices(range: TextRange, excluded: List<TextRange>): List<Int> {
        val own = mutableListOf<Int>()
        var next = 0
        var i = firstAtOrAfter(range.start)
        while (i < starts.size && starts[i] < range.end) {
            while (next < excluded.size && excluded[next].end <= starts[i]) next++
            val skip = next < excluded.size && starts[i] >= excluded[next].start
            if (skip) i = firstAtOrAfter(excluded[next].end) else own += i++
        }
        return own
    }

    /**
     * The start of the run of whitespace tokens that ends at [offset], a token start, without going before
     * [notBefore]; [offset] itself when no whitespace token ends there.
     */
    fun whitespaceStart(offset: Int, notBefore: Int): Int =
        layoutStart(offset, notBefore) { it.kind == TokenKind.WHITESPACE }

    /**
     * The start of the run of layout tokens that ends at [offset], without going before [notBefore]. A provider whose
     * lexer emits a line break as its own non-whitespace token can pass a predicate that treats it as layout too.
     */
    fun layoutStart(offset: Int, notBefore: Int, isLayout: (Token) -> Boolean): Int {
        val at = firstAtOrAfter(offset)
        if (at < starts.size && starts[at] != offset) return offset
        var i = at - 1
        while (i >= 0 && starts[i] >= notBefore && isLayout(tokens[i])) i--
        return if (i + 1 < at) starts[i + 1] else offset
    }

    /**
     * The end of the run of layout tokens that starts at [offset], without passing [limit]. A token that crosses
     * [limit] ends there, so a provider can attach a separator after a declaration without taking the next one.
     */
    fun layoutEnd(offset: Int, limit: Int, isLayout: (Token) -> Boolean): Int {
        var current = offset
        var i = firstAtOrAfter(offset)
        while (current < limit && i < starts.size && starts[i] == current) {
            val token = tokens[i]
            if (!isLayout(token)) break
            current = minOf(current + token.text.length, limit)
            i++
        }
        return current
    }

    private fun firstAtOrAfter(offset: Int): Int {
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] < offset) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        private const val CHECK_EVERY = 1024

        fun lex(
            lexer: Lexer,
            definition: ParserDefinition,
            text: CharSequence,
            cancellation: Cancellation,
            classify: (String, String, TokenKind) -> TokenKind = { _, _, kind -> kind },
        ): LexedTokens {
            val whitespace = definition.whitespaceTokens
            val comments = definition.commentTokens
            val starts = mutableListOf<Int>()
            val tokens = mutableListOf<Token>()
            lexer.start(text)
            while (true) {
                val type = lexer.tokenType ?: break
                if (tokens.size % CHECK_EVERY == 0) cancellation.checkCanceled()
                val kind = when {
                    whitespace.contains(type) -> TokenKind.WHITESPACE
                    comments.contains(type) -> TokenKind.COMMENT
                    else -> TokenKind.OTHER
                }
                val typeName = type.toString()
                val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                starts += lexer.tokenStart
                tokens += Token(typeName, tokenText, classify(typeName, tokenText, kind))
                lexer.advance()
            }
            return LexedTokens(starts.toIntArray(), tokens)
        }
    }
}
