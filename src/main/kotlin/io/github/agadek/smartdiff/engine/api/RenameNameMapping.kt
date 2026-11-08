package io.github.agadek.smartdiff.engine.api

/**
 * The shared name-mapping helper of the rename grouping (relationships/04): given the old text, the new text and the
 * grouped [Evidence.Reference] evidence, it replaces the text at each reference's old range with the text at that same
 * reference's new range and leaves every other character unchanged.
 *
 * The reference pass uses it to decide that a usage is the rename's only change, and the outline uses it to decide
 * that a declaration's only changes are grouped usages, so the two cannot disagree.
 */
object RenameNameMapping {

    /** [oldText] with every reference's old range replaced by the text at its new range in [newText]. */
    fun map(oldText: CharSequence, newText: CharSequence, references: List<Evidence.Reference>): String {
        val builder = StringBuilder(oldText)
        for (reference in references.sortedByDescending { it.oldRange.start }) {
            require(reference.oldRange.end <= oldText.length) {
                "reference old range ${reference.oldRange} is outside ${oldText.length} characters"
            }
            require(reference.newRange.end <= newText.length) {
                "reference new range ${reference.newRange} is outside ${newText.length} characters"
            }
            builder.replace(
                reference.oldRange.start,
                reference.oldRange.end,
                newText.subSequence(reference.newRange.start, reference.newRange.end).toString(),
            )
        }
        return builder.toString()
    }

    /**
     * [oldText]'s [oldRange] with every reference inside it mapped, compared with [newText]'s [newRange] under
     * [policy]. A reference outside either range is ignored.
     *
     * The comparison starts at the first non-whitespace character on each side, so leading whitespace and the node's
     * detail spans do not decide it (relationships/04 R10).
     */
    fun isRenameOnly(
        oldText: CharSequence,
        newText: CharSequence,
        references: List<Evidence.Reference>,
        oldRange: TextRange,
        newRange: TextRange,
        policy: WhitespacePolicy,
    ): Boolean {
        if (oldRange.end > oldText.length || newRange.end > newText.length) return false
        val inside = references.filter {
            it.oldRange.start >= oldRange.start && it.oldRange.end <= oldRange.end &&
                it.newRange.start >= newRange.start && it.newRange.end <= newRange.end
        }
        val mapped = map(oldText.subSequence(oldRange.start, oldRange.end), newText, inside.map {
            Evidence.Reference(
                it.symbol,
                TextRange(it.oldRange.start - oldRange.start, it.oldRange.end - oldRange.start),
                it.newRange,
            )
        })
        val newPart = newText.subSequence(newRange.start, newRange.end)
        return sameText(mapped, newPart, policy)
    }

    /** Whether [first] and [second] are equal under [policy]; comments and string literals are compared exactly. */
    fun sameText(first: CharSequence, second: CharSequence, policy: WhitespacePolicy): Boolean {
        if (policy == WhitespacePolicy.SIGNIFICANT) return first.toString() == second.toString()
        return tokens(first, policy) == tokens(second, policy)
    }

    /**
     * The text as the whitespace policy sees it: a token for each run of non-whitespace outside comments and strings,
     * and for each comment or string literal as written. A whitespace run counts as a line break, with its indentation
     * under [WhitespacePolicy.INDENTATION_SIGNIFICANT], or not at all.
     */
    private fun tokens(text: CharSequence, policy: WhitespacePolicy): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        val whitespace = StringBuilder()
        var index = 0
        var quote: String? = null
        var lineComment = false
        var blockComment = false

        fun flushText() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
        }

        fun flushWhitespace() {
            if (whitespace.isEmpty()) return
            val run = whitespace.toString()
            whitespace.setLength(0)
            val lastBreak = maxOf(run.lastIndexOf('\n'), run.lastIndexOf('\r'))
            if (lastBreak >= 0) {
                tokens += when (policy) {
                    WhitespacePolicy.LINE_BREAKS_SIGNIFICANT -> "\n"
                    WhitespacePolicy.INDENTATION_SIGNIFICANT -> "\n" + run.substring(lastBreak + 1)
                    else -> ""
                }
            }
        }

        while (index < text.length) {
            val c = text[index]
            if (lineComment) {
                current.append(c)
                if (c == '\n' || c == '\r') {
                    lineComment = false
                    flushText()
                }
                index++
                continue
            }
            if (blockComment) {
                current.append(c)
                if (c == '*' && index + 1 < text.length && text[index + 1] == '/') {
                    current.append('/')
                    index += 2
                    blockComment = false
                    flushText()
                    continue
                }
                index++
                continue
            }
            val activeQuote = quote
            if (activeQuote != null) {
                if (text.startsWith(activeQuote, index)) {
                    current.append(activeQuote)
                    index += activeQuote.length
                    quote = null
                    flushText()
                    continue
                }
                current.append(c)
                if (c == '\\' && index + 1 < text.length) {
                    current.append(text[index + 1])
                    index += 2
                    continue
                }
                index++
                continue
            }
            when {
                c == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                    flushWhitespace()
                    flushText()
                    lineComment = true
                }
                c == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                    flushWhitespace()
                    flushText()
                    blockComment = true
                }
                c == '"' || c == '\'' -> {
                    flushWhitespace()
                    flushText()
                    val opening = if (text.startsWith("\"\"\"", index)) "\"\"\"" else c.toString()
                    current.append(opening)
                    index += opening.length
                    quote = opening
                    continue
                }
                c.isWhitespace() -> {
                    flushText()
                    whitespace.append(c)
                }
                else -> {
                    flushWhitespace()
                    current.append(c)
                }
            }
            index++
        }
        flushText()
        flushWhitespace()
        return tokens
    }
}
