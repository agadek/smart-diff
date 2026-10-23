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
    fun whitespaceStart(offset: Int, notBefore: Int): Int {
        val at = firstAtOrAfter(offset)
        if (at < starts.size && starts[at] != offset) return offset
        var i = at - 1
        while (i >= 0 && starts[i] >= notBefore && tokens[i].kind == TokenKind.WHITESPACE) i--
        return if (i + 1 < at) starts[i + 1] else offset
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

        fun lex(lexer: Lexer, definition: ParserDefinition, text: CharSequence, cancellation: Cancellation): LexedTokens {
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
                starts += lexer.tokenStart
                tokens += Token(type.toString(), text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), kind)
                lexer.advance()
            }
            return LexedTokens(starts.toIntArray(), tokens)
        }
    }
}
