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
        var blockDepth = 0

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
            tokens += when (policy) {
                WhitespacePolicy.LINE_BREAKS_SIGNIFICANT -> if (lastBreak >= 0) "\n" else return
                WhitespacePolicy.SEPARATION_SIGNIFICANT -> if (lastBreak >= 0) "\n" else " "
                WhitespacePolicy.INDENTATION_SIGNIFICANT -> if (lastBreak >= 0) "\n" + run.substring(lastBreak + 1) else return
                else -> return
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
            if (blockDepth > 0) {
                // Kotlin and Scala nest block comments, so only the matching close ends the comment.
                if (c == '/' && index + 1 < text.length && text[index + 1] == '*') {
                    current.append("/*")
                    blockDepth++
                    index += 2
                    continue
                }
                current.append(c)
                if (c == '*' && index + 1 < text.length && text[index + 1] == '/') {
                    current.append('/')
                    index += 2
                    blockDepth--
                    if (blockDepth == 0) flushText()
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
                    current.append("/*")
                    index += 2
                    blockDepth = 1
                    continue
                }
                c == '"' || c == '`' || c == '\'' && isCharLiteral(text, index) -> {
                    flushWhitespace()
                    flushText()
                    val opening = when {
                        text.startsWith("\"\"\"", index) -> "\"\"\""
                        else -> c.toString()
                    }
                    current.append(opening)
                    index += opening.length
                    quote = opening
                    continue
                }
                // A Scala symbol literal such as 'foo is code, not an opening quote of a char literal.
                c == '\'' -> {
                    flushWhitespace()
                    current.append(c)
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

    /**
     * Whether the quote at [index] opens a char literal rather than a Scala symbol. A char literal has one character
     * or one escape and then a closing quote on the same line: `'a'`, `'\n'`, `'\u0041'`. The check is deliberately
     * lexical so it needs no language: Kotlin has char literals, Scala 2 has both chars and symbols, and Scala 3 uses
     * `'` for quotes as well.
     */
    private fun isCharLiteral(text: CharSequence, index: Int): Boolean {
        var i = index + 1
        if (i >= text.length) return false
        if (text[i] == '\\') {
            while (i < text.length && i - index <= 12) {
                when {
                    text[i] == '\\' -> i += 2
                    text[i] == '\'' -> return true
                    text[i] == '\n' || text[i] == '\r' -> return false
                    else -> i++
                }
            }
            return false
        }
        return i + 1 < text.length && text[i + 1] == '\'' && text[i] != '\n' && text[i] != '\r'
    }
}
