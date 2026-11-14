package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/**
 * Classifies the own source of a matched declaration, or of the file region (technical specification §8). The first
 * step that applies decides:
 * 1. identical token texts, whitespace included, and the same signature → [Content.UNCHANGED], even with a parse
 *    error: the same source parses the same way on both sides, so a parse error marks only code that changed;
 * 2. a parse error on either side → [Content.UNANALYZED], even when the signature changed;
 * 3. the signature changed (rule M3) → [Content.CHANGED];
 * 4. equal after the whitespace policy's normalization → [Content.FORMATTING_ONLY], otherwise [Content.CHANGED].
 *
 * Comments are compared by exact text, including the whitespace inside them.
 */
object ContentClassifier {

    /** Stands for a whitespace run with a line break under [WhitespacePolicy.LINE_BREAKS_SIGNIFICANT] and [WhitespacePolicy.SEPARATION_SIGNIFICANT]. */
    private val LINE_BREAK = Token("WHITESPACE", "⏎", TokenKind.WHITESPACE)

    /** Stands for a whitespace run without a line break under [WhitespacePolicy.SEPARATION_SIGNIFICANT]. */
    private val SPACE = Token("WHITESPACE", " ", TokenKind.WHITESPACE)

    /** Stands for a whitespace run with a line break under [WhitespacePolicy.INDENTATION_SIGNIFICANT]. */
    private fun lineBreak(indentation: String) = Token("WHITESPACE", "⏎$indentation", TokenKind.WHITESPACE)

    fun classify(
        old: List<Token>,
        new: List<Token>,
        policy: WhitespacePolicy,
        parseError: Boolean,
        signatureChanged: Boolean = false,
    ): Content {
        val oldContent = old.filterNot { it.kind == TokenKind.SEPARATOR }
        val newContent = new.filterNot { it.kind == TokenKind.SEPARATOR }
        return when {
            !signatureChanged && oldContent.map { it.text } == newContent.map { it.text } -> Content.UNCHANGED
            parseError -> Content.UNANALYZED
            signatureChanged -> Content.CHANGED
            normalize(oldContent, policy) == normalize(newContent, policy) -> Content.FORMATTING_ONLY
            else -> Content.CHANGED
        }
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
        fun endsWithLineBreak(token: Token): Boolean =
            token.text.lastOrNull() == '\n' || token.text.lastOrNull() == '\r'
        fun endRun() {
            if (run.isEmpty()) return
            val text = run.toString()
            run.setLength(0)
            val lastBreak = maxOf(text.lastIndexOf('\n'), text.lastIndexOf('\r'))
            when (policy) {
                WhitespacePolicy.LINE_BREAKS_SIGNIFICANT ->
                    if (lastBreak >= 0) normalized += LINE_BREAK
                WhitespacePolicy.SEPARATION_SIGNIFICANT -> {
                    if (lastBreak >= 0) {
                        normalized += LINE_BREAK
                    } else {
                        // Indentation after a line break is not a token separator: drop it so the amount of indentation
                        // does not matter. A line break may be a WHITESPACE token (emitted as LINE_BREAK) or a
                        // non-whitespace token whose text ends with a line break.
                        val afterLineBreak = normalized.lastOrNull()?.let { it == LINE_BREAK || endsWithLineBreak(it) } == true
                        if (!afterLineBreak) normalized += SPACE
                    }
                }
                // The indentation of the next line: the run's text after its last line break.
                WhitespacePolicy.INDENTATION_SIGNIFICANT ->
                    if (lastBreak >= 0) normalized += lineBreak(text.substring(lastBreak + 1))
                else -> {}
            }
        }
        for (token in tokens) {
            when (token.kind) {
                TokenKind.WHITESPACE -> run.append(token.text)
                TokenKind.SEPARATOR -> Unit
                else -> {
                    endRun()
                    normalized += token
                }
            }
        }
        endRun()
        return normalized
    }
}
