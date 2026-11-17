package io.github.agadek.smartdiff.engine.declarations.psi

import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind

/** Whether the canonical text of an escape rule writes [c] as itself (declarations/08, 09). */
internal fun printableInLiteral(c: Char): Boolean {
    if (Character.isISOControl(c) || !Character.isDefined(c)) return false
    return when (Character.getType(c).toByte()) {
        Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> c == ' '
        else -> true
    }
}

/** Whether [c] is a hexadecimal digit. */
internal fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/**
 * The redundant parentheses scan shared by the Kotlin and Scala rules (declarations/09 R7, R8, R11, R12): removes, from
 * the inside out, a matched pair whose content is not empty and has no [excludedTopLevel] token at its top level and no
 * [excludedAnywhere] token, when the meaningful tokens around it fit one row of [allowedAfter]. A statement end is
 * [STATEMENT_END] in that table: one of [statementEnds], the end of the tokens, or a line break followed by one of
 * [startsStatement] while the pair is not inside `(` or `[`.
 */
internal class ParenthesesScan(
    private val lpar: String,
    private val rpar: String,
    private val lbracket: String,
    private val opens: Set<String>,
    private val closes: Set<String>,
    private val excludedTopLevel: Set<String>,
    private val excludedAnywhere: Set<String>,
    private val statementEnds: Set<String>,
    private val startsStatement: (Token) -> Boolean,
    /** The tokens allowed after the `)` for each token before the `(`, [STATEMENT_END] standing for a statement end. */
    private val allowedAfter: (before: String) -> Set<String>?,
    /** Whether the row of [before] also needs the pair to be directly inside `(` or `[` (an argument). */
    private val needsArgumentList: (before: String) -> Boolean,
) {

    fun rewrite(tokens: List<Token>): List<Token> {
        var current = tokens
        while (true) {
            val pair = removable(current) ?: return current
            current = current.filterIndexed { index, _ -> index != pair.first && index != pair.second }
        }
    }

    private fun meaningful(token: Token) = token.kind != TokenKind.WHITESPACE && token.kind != TokenKind.COMMENT

    /** The innermost removable pair, as the indices of its `(` and `)`, or null when none is. */
    private fun removable(tokens: List<Token>): Pair<Int, Int>? {
        val indices = tokens.indices.filter { meaningful(tokens[it]) }
        val stack = ArrayDeque<Int>() // positions in [indices] of open brackets of any kind
        for ((position, index) in indices.withIndex()) {
            val type = tokens[index].type
            when (type) {
                in opens -> stack.addLast(position)
                in closes -> {
                    val open = stack.removeLastOrNull() ?: return null // unbalanced: leave the tokens as they are
                    if (type == rpar && tokens[indices[open]].type == lpar) {
                        val enclosing = stack.lastOrNull()?.let { tokens[indices[it]].type }
                        if (isRedundant(tokens, indices, open, position, enclosing)) return indices[open] to index
                    }
                }
            }
        }
        return null
    }

    private fun isRedundant(tokens: List<Token>, indices: List<Int>, open: Int, close: Int, enclosing: String?): Boolean {
        if (close == open + 1) return false // empty content
        val before = indices.getOrNull(open - 1)?.let { tokens[it].type } ?: return false
        val allowed = allowedAfter(before) ?: return false
        val inArgumentList = enclosing == lpar || enclosing == lbracket
        if (needsArgumentList(before) && !inArgumentList) return false
        if (!fitsAfter(tokens, indices, close, allowed, inArgumentList)) return false
        var depth = 0
        for (position in open + 1 until close) {
            val type = tokens[indices[position]].type
            when {
                type in excludedAnywhere -> return false
                type in opens -> depth++
                type in closes -> depth--
                depth == 0 && type in excludedTopLevel -> return false
            }
        }
        return true
    }

    private fun fitsAfter(tokens: List<Token>, indices: List<Int>, close: Int, allowed: Set<String>, inArgumentList: Boolean): Boolean {
        val nextIndex = indices.getOrNull(close + 1)
        if (nextIndex == null) return STATEMENT_END in allowed
        val next = tokens[nextIndex]
        if (next.type in allowed) return true
        if (STATEMENT_END !in allowed) return false
        if (next.type in statementEnds) return true
        if (inArgumentList) return false
        val lineBreak = (indices[close] + 1 until nextIndex).any { i ->
            tokens[i].kind == TokenKind.WHITESPACE && (tokens[i].text.contains('\n') || tokens[i].text.contains('\r'))
        }
        return lineBreak && startsStatement(next)
    }

    companion object {
        /** The table entry for a statement end. */
        const val STATEMENT_END = "<statement end>"
    }
}
