package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind

/**
 * The tokens of one side, built from a [TreeSitterTree] (engines/05-tree-sitter-details §6 step 3): every leaf in order,
 * a whole string literal as one token, and the gaps between them as whitespace. A token belongs to the range that holds
 * its start, as in the PSI providers' lexer pass.
 */
internal class TreeSitterTokens private constructor(private val starts: IntArray, private val tokens: List<Token>) {

    /** The tokens that start in [range] but in none of [excluded], which are sorted, disjoint and inside [range]. */
    fun own(range: TextRange, excluded: List<TextRange>): List<Token> = ownIndices(range, excluded).map { tokens[it] }

    /** The start offsets of the tokens [own] returns, in the same order. */
    fun ownStarts(range: TextRange, excluded: List<TextRange>): List<Int> = ownIndices(range, excluded).map { starts[it] }

    /** The start of the run of whitespace tokens that ends at [offset], without going before [notBefore]. */
    fun whitespaceStart(offset: Int, notBefore: Int): Int {
        val at = firstAtOrAfter(offset)
        if (at < starts.size && starts[at] != offset) return offset
        var i = at - 1
        while (i >= 0 && starts[i] >= notBefore && tokens[i].kind == TokenKind.WHITESPACE) i--
        return if (i + 1 < at) starts[i + 1] else offset
    }

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
        /**
         * The tokens of [text] as [tree] covers it. [comments] and [strings] are symbol names: a comment leaf is a
         * comment token, and a string node is one token, so whitespace inside a string literal is content (R17).
         * Text between leaves is whitespace; any other text there, which a grammar leaves only where macro recovery
         * read it as blank space, is content tokens, one per word, so it is never ignored. A line break at an offset in [lineBreaks] is a content token of its own, and a
         * leaf whose symbol is in [splitLeaves] is split into words and whitespace (engines/05-tree-sitter-details §7).
         */
        fun of(
            tree: TreeSitterTree,
            names: TreeSitterNames,
            text: CharSequence,
            comments: Set<String>,
            strings: Set<String>,
            lineBreaks: Set<Int> = emptySet(),
            splitLeaves: Set<String> = emptySet(),
        ): TreeSitterTokens {
            val starts = mutableListOf<Int>()
            val tokens = mutableListOf<Token>()
            var position = 0
            fun add(start: Int, token: Token) {
                starts += start
                tokens += token
            }
            /** Whitespace from [position] to [end], with each line break of [lineBreaks] as a content token. */
            fun whitespace(end: Int) {
                var from = position
                for (at in position until end) {
                    if (at !in lineBreaks) continue
                    if (at > from) add(from, Token("WHITE_SPACE", text.substring(from, at), TokenKind.WHITESPACE))
                    add(at, Token(LINE_BREAK, "\n", TokenKind.OTHER))
                    from = at + 1
                }
                if (end > from) add(from, Token("WHITE_SPACE", text.substring(from, end), TokenKind.WHITESPACE))
                position = end
            }
            /**
             * Text between leaves: whitespace, or source the parse did not see, such as a macro that macro recovery read
             * as blank space (engines/06 R5), split into words, each one content token, and whitespace.
             */
            fun gap(end: Int) {
                while (position < end) {
                    var next = position
                    if (text[position].isWhitespace()) {
                        while (next < end && text[next].isWhitespace()) next++
                        whitespace(next)
                    } else {
                        while (next < end && !text[next].isWhitespace()) next++
                        add(position, Token("TEXT", text.substring(position, next), TokenKind.OTHER))
                        position = next
                    }
                }
            }
            /** A leaf of [splitLeaves]: each run of whitespace is whitespace and each other run one content token. */
            fun split(start: Int, end: Int, type: String) {
                var at = start
                while (at < end) {
                    var next = at
                    if (text[at].isWhitespace()) {
                        while (next < end && text[next].isWhitespace()) next++
                        whitespace(next)
                    } else {
                        while (next < end && !text[next].isWhitespace()) next++
                        add(at, Token(type, text.substring(at, next), TokenKind.OTHER))
                        position = next
                    }
                    at = next
                }
            }
            fun token(start: Int, end: Int, type: String, kind: TokenKind) {
                if (start < position) return
                gap(start)
                if (end > start) {
                    if (type in splitLeaves) {
                        split(start, end, type)
                        return
                    }
                    add(start, Token(type, text.substring(start, end), kind))
                    position = end
                }
            }
            // The tree is in pre-order, so a node's children follow it; skip the descendants of a string node.
            var skipUntil = -1
            val hasChild = BooleanArray(tree.size)
            for (node in 1 until tree.size) hasChild[tree.parent(node)] = true
            for (node in 0 until tree.size) {
                val start = tree.start(node)
                if (start < skipUntil) continue
                val symbol = names.symbols.getOrElse(tree.symbol(node)) { "" }
                when {
                    symbol in strings -> {
                        token(start, tree.end(node), symbol, TokenKind.OTHER)
                        skipUntil = tree.end(node)
                    }
                    !hasChild[node] -> {
                        val kind = if (symbol in comments) TokenKind.COMMENT else TokenKind.OTHER
                        val type = if (tree.isNamed(node)) symbol else text.substring(start, tree.end(node))
                        token(start, tree.end(node), type, kind)
                    }
                }
            }
            gap(text.length)
            return TreeSitterTokens(starts.toIntArray(), tokens)
        }

        /** The type of a line break that is content (§7). */
        const val LINE_BREAK = "LINE_BREAK"
    }
}
