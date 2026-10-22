package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy

/**
 * Classifies the own source of a matched declaration, or of the file region (technical specification §8). The first
 * step that applies decides:
 * 1. a parse error on either side → [Content.UNANALYZED], even when the signature changed;
 * 2. the signature changed (rule M3) → [Content.CHANGED];
 * 3. identical token texts, whitespace included → [Content.UNCHANGED];
 * 4. equal after the whitespace policy's normalization → [Content.FORMATTING_ONLY], otherwise [Content.CHANGED].
 *
 * Comments are compared by exact text, including the whitespace inside them.
 */
object ContentClassifier {

    /** Stands for a whitespace run with a line break under [WhitespacePolicy.LINE_BREAKS_SIGNIFICANT]. */
    private val LINE_BREAK = Token("WHITESPACE", "⏎", TokenKind.WHITESPACE)

    /** Stands for a whitespace run with a line break under [WhitespacePolicy.INDENTATION_SIGNIFICANT]. */
    private fun lineBreak(indentation: String) = Token("WHITESPACE", "⏎$indentation", TokenKind.WHITESPACE)

    fun classify(
        old: List<Token>,
        new: List<Token>,
        policy: WhitespacePolicy,
        parseError: Boolean,
        signatureChanged: Boolean = false,
    ): Content = when {
        parseError -> Content.UNANALYZED
        signatureChanged -> Content.CHANGED
        old.map { it.text } == new.map { it.text } -> Content.UNCHANGED
        normalize(old, policy) == normalize(new, policy) -> Content.FORMATTING_ONLY
        else -> Content.CHANGED
    }

    /**
     * The `(type, text)` pairs that count under [policy]. A run of adjacent whitespace tokens counts as one: own tokens
     * skip the children's ranges, so the whitespace before and after a child can end up next to each other, and an
     * added or removed child must not change the count of line breaks around it.
     */
    internal fun normalize(tokens: List<Token>, policy: WhitespacePolicy): List<Token> {
        if (policy == WhitespacePolicy.SIGNIFICANT) return tokens
        val normalized = mutableListOf<Token>()
        val run = StringBuilder() // the text of the current whitespace run
        fun endRun() {
            val lastBreak = maxOf(run.lastIndexOf("\n"), run.lastIndexOf("\r"))
            if (lastBreak >= 0) {
                when (policy) {
                    WhitespacePolicy.LINE_BREAKS_SIGNIFICANT -> normalized += LINE_BREAK
                    // The indentation of the next line: the run's text after its last line break.
                    WhitespacePolicy.INDENTATION_SIGNIFICANT -> normalized += lineBreak(run.substring(lastBreak + 1))
                    else -> {}
                }
            }
            run.setLength(0)
        }
        for (token in tokens) {
            if (token.kind == TokenKind.WHITESPACE) {
                run.append(token.text)
            } else {
                endRun()
                normalized += token
            }
        }
        endRun()
        return normalized
    }
}
