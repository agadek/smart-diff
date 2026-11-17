package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.BracketDepth
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.api.LineBreakRules
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
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
            runCatching { comparable(normalize(oldContent, policy), policy) == comparable(normalize(newContent, policy), policy) }.getOrDefault(false) -> Content.FORMATTING_ONLY
            else -> Content.CHANGED
        }
    }

    /**
     * Whether a declaration's own source is the same on both sides once comments are left out and whitespace is
     * compared under [policy], as for formatting-only (outline/11 R3, R5). Never with a parse error or a changed
     * signature (R4), never for identical sources, and only when either side has a comment. A removed comment leaves
     * its line breaks, so a block comment spanning lines still separates what was around it. Strings are never left
     * out, so a change inside a string, a docstring included, keeps the declaration changed.
     */
    fun isCommentOnly(
        old: List<Token>,
        new: List<Token>,
        policy: WhitespacePolicy,
        parseError: Boolean,
        signatureChanged: Boolean = false,
    ): Boolean {
        return try {
            if (parseError || signatureChanged) return false
            val oldContent = old.filterNot { it.kind == TokenKind.SEPARATOR }
            val newContent = new.filterNot { it.kind == TokenKind.SEPARATOR }
            if (oldContent.map { it.text } == newContent.map { it.text }) return false
            if (oldContent.none { it.kind == TokenKind.COMMENT } && newContent.none { it.kind == TokenKind.COMMENT }) return false
            if (policy == WhitespacePolicy.SIGNIFICANT) {
                sameWithoutComments(withoutComments(oldContent), withoutComments(newContent))
            } else {
                // Whitespace is compared as for formatting-only (outline/11 R3), including the Scala 3 shift.
                val without = { tokens: List<Token> ->
                    comparable(normalize(tokens.filterNot { it.kind == TokenKind.COMMENT }, policy), policy)
                }
                without(oldContent) == without(newContent)
            }
        } catch (e: Exception) {
            // A pure comparison must never turn a changed node into a claim about its source (outline/11, failure).
            false
        }
    }

    /**
     * The smallest set of [rules] that makes a declaration's own source the same on both sides, with whitespace compared
     * under [policy] as for formatting-only (declarations/08 R4, R6); empty when no set does. Never with a parse error,
     * a changed signature or a comment change (R5), and never for identical sources. [rules] are applied in their
     * order; a rule is in the set when leaving it out, and applying the others, makes the sides differ.
     */
    fun rewriteRules(
        old: List<Token>,
        new: List<Token>,
        policy: WhitespacePolicy,
        parseError: Boolean,
        signatureChanged: Boolean = false,
        rules: List<EquivalenceRuleImpl>,
    ): Set<EquivalenceRule> {
        return try {
            if (rules.isEmpty() || parseError || signatureChanged) return emptySet()
            val oldContent = old.filterNot { it.kind == TokenKind.SEPARATOR }
            val newContent = new.filterNot { it.kind == TokenKind.SEPARATOR }
            if (oldContent.map { it.text } == newContent.map { it.text }) return emptySet()
            val comments = { tokens: List<Token> -> tokens.filter { it.kind == TokenKind.COMMENT }.map { it.text } }
            if (comments(oldContent) != comments(newContent)) return emptySet()
            fun same(applied: List<EquivalenceRuleImpl>): Boolean {
                val rewritten = { tokens: List<Token> ->
                    comparable(normalize(applied.fold(tokens) { acc, rule -> rule.rewrite(acc) }, policy), policy)
                }
                return rewritten(oldContent) == rewritten(newContent)
            }
            if (!same(rules)) return emptySet()
            rules.filter { rule -> !same(rules - rule) }.mapTo(LinkedHashSet()) { it.rule }
        } catch (e: Exception) {
            // A pure comparison must never turn a changed node into a claim about its source (declarations/08, failure).
            emptySet()
        }
    }

    /** A declaration with more own tokens than this on either side is not compared by tokens (declarations/10 R10). */
    const val MAX_RUN_TOKENS = 20_000

    /** A comparison whose edit distance, removed plus added tokens, passes this has no changed runs (declarations/10 R10). */
    const val MAX_RUN_EDITS = 2_000

    /**
     * The changed runs of a changed or formatting-only declaration or file region (declarations/10 R2 to R10): each
     * stretch of tokens the token comparison found different, as a range on the old side, the new side, or both. Null
     * when the source is not compared by tokens: a parse error on either side, no token offsets, or the limits of R10.
     *
     * [old] and [new] are the own tokens to compare, without leading whitespace, each with its start offset in
     * [oldStarts] and [newStarts]. A rename candidate's old tokens already carry the new name. Whitespace counts as for
     * formatting-only under [policy] (R4), a comment by its exact text (R5). Runs separated only by whitespace are one
     * run (R6). Runs come in old-side order, then new-side order for runs without an old side.
     *
     * [oldOwn] and [newOwn], when given, are the source ranges the tokens own, such as a declaration's range minus its
     * children's: a run is cut to them, so no run covers a child, and a run cut in pieces gives one run per piece.
     *
     * [oldLengths], when given, are the source lengths of the old tokens, for a rename whose old tokens carry the new
     * name. [oldShiftSource] and [newShiftSource], when given, are the tokens the Scala 3 shift is measured on, the
     * leading whitespace included, as classification measures it (declarations/07 R8).
     */
    fun changedRuns(
        old: List<Token>,
        oldStarts: List<Int>,
        new: List<Token>,
        newStarts: List<Int>,
        policy: WhitespacePolicy,
        parseError: Boolean,
        cancellation: Cancellation = Cancellation.NONE,
        oldOwn: List<TextRange>? = null,
        newOwn: List<TextRange>? = null,
        oldLengths: List<Int>? = null,
        oldShiftSource: List<Token>? = null,
        newShiftSource: List<Token>? = null,
    ): List<RangePair>? {
        cancellation.checkCanceled()
        if (parseError) return null
        if (old.size != oldStarts.size || new.size != newStarts.size) return null
        if ((old.isNotEmpty() && oldStarts.isEmpty()) || (new.isNotEmpty() && newStarts.isEmpty())) return null
        if (old.size > MAX_RUN_TOKENS || new.size > MAX_RUN_TOKENS) return null
        if (oldLengths != null && oldLengths.size != old.size) return null
        val a = comparableWithOffsets(normalizeWithOffsets(old, oldStarts, policy, oldLengths), policy, oldShiftSource)
        val b = comparableWithOffsets(normalizeWithOffsets(new, newStarts, policy), policy, newShiftSource)
        val matches = TokenDiff(a, b, { x, y -> x.first.type == y.first.type && x.first.text == y.first.text }, cancellation)
            .matches(MAX_RUN_EDITS) ?: return null
        return cut(runs(a, b, matches), oldOwn, newOwn)
    }

    /**
     * [runs] cut to [oldOwn] and [newOwn]; the pieces of a cut run pair up in order, the rest stand alone. An empty
     * piece, such as Python's `DEDENT` token, which has no text, is left out: there is nothing to highlight.
     */
    private fun cut(runs: List<RangePair>, oldOwn: List<TextRange>?, newOwn: List<TextRange>?): List<RangePair> {
        fun pieces(range: TextRange?, own: List<TextRange>?): List<TextRange> = when {
            range == null -> emptyList()
            own == null -> listOfNotNull(range.takeIf { it.length > 0 })
            else -> own.mapNotNull { part ->
                val start = maxOf(part.start, range.start)
                val end = minOf(part.end, range.end)
                if (start < end) TextRange(start, end) else null
            }
        }
        return runs.flatMap { run ->
            val old = pieces(run.old, oldOwn)
            val new = pieces(run.new, newOwn)
            (0 until maxOf(old.size, new.size)).map { RangePair(old.getOrNull(it), new.getOrNull(it)) }
        }.sortedWith(compareBy<RangePair>({ it.old == null }, { it.old?.start ?: it.new!!.start }))
    }

    /** Joins the unmatched stretches between [matches] into runs, across matched whitespace only (R6). */
    private fun runs(a: List<Pair<Token, TextRange>>, b: List<Pair<Token, TextRange>>, matches: List<IntArray>): List<RangePair> {
        val runs = mutableListOf<RangePair>()
        var oldFrom = -1
        var oldTo = -1
        var newFrom = -1
        var newTo = -1
        fun span(tokens: List<Pair<Token, TextRange>>, from: Int, to: Int): TextRange? =
            if (from < 0) null else TextRange(tokens[from].second.start, tokens[to].second.end)
        fun close() {
            if (oldFrom >= 0 || newFrom >= 0) runs += RangePair(span(a, oldFrom, oldTo), span(b, newFrom, newTo))
            oldFrom = -1; oldTo = -1; newFrom = -1; newTo = -1
        }
        var previousOld = -1
        var previousNew = -1
        // Whether only matched whitespace lies between the open run and the current position.
        var joinable = false
        for (index in 0..matches.size) {
            val nextOld = if (index < matches.size) matches[index][0] else a.size
            val nextNew = if (index < matches.size) matches[index][1] else b.size
            val gapOld = previousOld + 1 < nextOld
            val gapNew = previousNew + 1 < nextNew
            if (gapOld || gapNew) {
                if (!joinable) close()
                if (gapOld) {
                    if (oldFrom < 0) oldFrom = previousOld + 1
                    oldTo = nextOld - 1
                }
                if (gapNew) {
                    if (newFrom < 0) newFrom = previousNew + 1
                    newTo = nextNew - 1
                }
                joinable = true
            }
            if (index < matches.size && a[nextOld].first.kind != TokenKind.WHITESPACE) joinable = false
            previousOld = nextOld
            previousNew = nextNew
        }
        close()
        return runs.sortedWith(compareBy<RangePair>({ it.old == null }, { it.old?.start ?: it.new!!.start }))
    }

    /**
     * [normalize] with each token's source range (declarations/10 details, step 2). A whitespace run that becomes one
     * token has the range from the first to the last whitespace token it replaces; a dropped run has no token. As in
     * classification, a [TokenKind.SEPARATOR] token is left out, so it never splits a whitespace run. [lengths], when
     * given, are the tokens' source lengths, which differ from their text for a renamed old side.
     */
    internal fun normalizeWithOffsets(
        tokens: List<Token>,
        starts: List<Int>,
        policy: WhitespacePolicy,
        lengths: List<Int>? = null,
    ): List<Pair<Token, TextRange>> {
        require(tokens.size == starts.size) { "every token needs a start" }
        fun range(index: Int) = TextRange(starts[index], starts[index] + (lengths?.get(index) ?: tokens[index].text.length))
        if (policy == WhitespacePolicy.SIGNIFICANT) {
            return tokens.indices.filter { tokens[it].kind != TokenKind.SEPARATOR }.map { tokens[it] to range(it) }
        }
        val normalized = mutableListOf<Pair<Token, TextRange>>()
        val run = StringBuilder()
        var runStart = -1
        var runEnd = -1
        val depth = BracketDepth()
        var previous: String? = null
        fun endsWithLineBreak(token: Token): Boolean =
            token.text.lastOrNull() == '\n' || token.text.lastOrNull() == '\r'
        fun endRun(next: String?) {
            if (run.isEmpty()) return
            val text = run.toString()
            val whole = TextRange(runStart, runEnd)
            run.setLength(0)
            val lastBreak = maxOf(text.lastIndexOf('\n'), text.lastIndexOf('\r'))
            if (lastBreak < 0) {
                if (policy == WhitespacePolicy.SEPARATION_SIGNIFICANT) {
                    val afterLineBreak = normalized.lastOrNull()?.first?.let { it == LINE_BREAK || endsWithLineBreak(it) } == true
                    if (!afterLineBreak) normalized += SPACE to whole
                }
                return
            }
            if (LineBreakRules.ignorable(policy, previous, next, depth)) return
            when {
                policy.countsLineBreaks -> normalized += LINE_BREAK to whole
                policy.countsIndentation -> normalized += lineBreak(text.substring(lastBreak + 1)) to whole
                policy == WhitespacePolicy.SEPARATION_SIGNIFICANT -> normalized += LINE_BREAK to whole
            }
        }
        for ((index, token) in tokens.withIndex()) {
            when (token.kind) {
                TokenKind.WHITESPACE -> {
                    if (run.isEmpty()) runStart = starts[index]
                    run.append(token.text)
                    runEnd = range(index).end
                }
                TokenKind.SEPARATOR -> Unit
                else -> {
                    endRun(token.text)
                    normalized += token to range(index)
                    LineBreakRules.track(depth, token.text)
                    previous = token.text
                }
            }
        }
        endRun(null)
        return normalized
    }

    /** [normalized] as compared under [policy], keeping each token's range: Scala 3 strips the common shift (R8). */
    private fun comparableWithOffsets(
        normalized: List<Pair<Token, TextRange>>,
        policy: WhitespacePolicy,
        shiftSource: List<Token>?,
    ): List<Pair<Token, TextRange>> {
        if (policy != WhitespacePolicy.SCALA3_INDENTATION) return normalized
        val tokens = normalized.map { it.first }
        val shift = indentationShift(shiftSource?.let { normalize(it.filterNot { t -> t.kind == TokenKind.SEPARATOR }, policy) } ?: tokens)
        return applyIndentShift(tokens, shift).zip(normalized) { token, (_, range) -> token to range }
    }

    /** The line breaks of [text], so two whitespace runs can be compared by line breaks only. */
    private fun lineBreaks(text: String): String = text.replace("\r\n", "\n").filter { it == '\n' || it == '\r' }

    /** A whitespace run, or another token, with comments left out under [WhitespacePolicy.SIGNIFICANT]. */
    private sealed interface Part {
        data class Run(val text: String, val touchedComment: Boolean) : Part
        data class Other(val type: String, val text: String) : Part
    }

    /** Adjacent whitespace and the comments between it become one run, marked when it touched a removed comment. */
    private fun withoutComments(tokens: List<Token>): List<Part> {
        val parts = mutableListOf<Part>()
        val run = StringBuilder()
        var touched = false
        var inRun = false
        fun endRun() {
            if (inRun) parts += Part.Run(run.toString(), touched)
            run.setLength(0)
            touched = false
            inRun = false
        }
        for (token in tokens) {
            when (token.kind) {
                TokenKind.WHITESPACE -> { run.append(token.text); inRun = true }
                TokenKind.COMMENT -> { touched = true; inRun = true }
                else -> { endRun(); parts += Part.Other(token.type, token.text) }
            }
        }
        endRun()
        return parts.filterNot { it is Part.Run && it.text.isEmpty() && !it.touchedComment }
    }

    /**
     * Whether two part lists are the same, where a run that touched a removed comment on either side is compared only
     * by whether it has a line break, and every other run exactly (outline/11 details, step 4).
     */
    private fun sameWithoutComments(old: List<Part>, new: List<Part>): Boolean {
        // A run that is only a removed comment, with no whitespace or line break left, is no run at all.
        fun List<Part>.clean() = filterNot { it is Part.Run && it.text.isEmpty() }
        val a = old.clean()
        val b = new.clean()
        if (a.size != b.size) return false
        return a.zip(b).all { (x, y) ->
            when {
                x is Part.Run && y is Part.Run ->
                    if (x.touchedComment || y.touchedComment) lineBreaks(x.text).isNotEmpty() == lineBreaks(y.text).isNotEmpty() else x.text == y.text
                else -> x == y
            }
        }
    }

    /**
     * The `(type, text)` pairs that count under [policy]. A run of adjacent whitespace tokens counts as one: own tokens
     * skip the children's ranges, so the whitespace before and after a child can end up next to each other, and an
     * added or removed child must not change the count of line breaks around it.
     *
     * Under the Kotlin and Scala policies a run is dropped when [LineBreakRules.ignorable] says the language ignores
     * its line break (declarations/07 R1–R7); bracket state is kept over the same declaration's own tokens (R4).
     */
    internal fun normalize(tokens: List<Token>, policy: WhitespacePolicy): List<Token> {
        if (policy == WhitespacePolicy.SIGNIFICANT) return tokens
        val normalized = mutableListOf<Token>()
        val run = StringBuilder() // the text of the current whitespace run
        val depth = BracketDepth()
        var previous: String? = null
        fun endsWithLineBreak(token: Token): Boolean =
            token.text.lastOrNull() == '\n' || token.text.lastOrNull() == '\r'
        fun endRun(next: String?) {
            if (run.isEmpty()) return
            val text = run.toString()
            run.setLength(0)
            val lastBreak = maxOf(text.lastIndexOf('\n'), text.lastIndexOf('\r'))
            if (lastBreak < 0) {
                if (policy == WhitespacePolicy.SEPARATION_SIGNIFICANT) {
                    // Indentation after a line break is not a token separator: drop it so the amount of indentation
                    // does not matter. A line break may be a WHITESPACE token (emitted as LINE_BREAK) or a
                    // non-whitespace token whose text ends with a line break.
                    val afterLineBreak = normalized.lastOrNull()?.let { it == LINE_BREAK || endsWithLineBreak(it) } == true
                    if (!afterLineBreak) normalized += SPACE
                }
                return
            }
            if (LineBreakRules.ignorable(policy, previous, next, depth)) return
            when {
                policy.countsLineBreaks -> normalized += LINE_BREAK
                policy.countsIndentation -> normalized += lineBreak(text.substring(lastBreak + 1))
                policy == WhitespacePolicy.SEPARATION_SIGNIFICANT -> normalized += LINE_BREAK
            }
        }
        for (token in tokens) {
            when (token.kind) {
                TokenKind.WHITESPACE -> run.append(token.text)
                TokenKind.SEPARATOR -> Unit
                else -> {
                    endRun(token.text)
                    normalized += token
                    LineBreakRules.track(depth, token.text)
                    previous = token.text
                }
            }
        }
        endRun(null)
        return normalized
    }

    /** The normalized tokens as compared under [policy]: Scala 3 strips the whole declaration's common shift (R8). */
    private fun comparable(normalized: List<Token>, policy: WhitespacePolicy): List<Token> =
        if (policy == WhitespacePolicy.SCALA3_INDENTATION) applyIndentShift(normalized, indentationShift(normalized)) else normalized

    /** The common indentation of every counted line-break run in [normalized], or null when it is not uniform (R8). */
    internal fun indentationShift(normalized: List<Token>): String? =
        LineBreakRules.commonIndent(normalized.filter { it.kind == TokenKind.WHITESPACE && it.text.startsWith("⏎") }
            .map { it.text.drop(1) })

    /** [normalized] with [shift] removed from the indentation of every counted line-break run (R8). */
    internal fun applyIndentShift(normalized: List<Token>, shift: String?): List<Token> {
        if (shift.isNullOrEmpty()) return normalized
        return normalized.map { token ->
            if (token.kind == TokenKind.WHITESPACE && token.text.startsWith("⏎")) {
                token.copy(text = "⏎" + token.text.drop(1).removePrefix(shift))
            } else {
                token
            }
        }
    }
}
