package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipEndpoint
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.Statement
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import java.util.EnumSet

/**
 * Finds code moves (relationships/08): runs of whole statements removed from one matched declaration and added in one
 * run to the same or another matched declaration of the file. It reads the changed runs that fine-grained differencing
 * found for each changed declaration (declarations/10), and runs after matching, classification, extraction and
 * inlining detection, so it never changes how a declaration is matched, classified or labelled (R19).
 *
 * The steps follow relationships/08 details, The algorithm. Every sort has a total order on source offsets, so equal
 * inputs give equal moves.
 */
internal object CodeMoveDetector {

    /** R5: the least similarity of a statement and its counterpart. */
    const val SIMILARITY = 0.8

    /** R4: the least number of tokens other than whitespace and comments in a code move. */
    const val MIN_TOKENS = 12

    /** Step 2: the share of a statement's tokens that must lie in changed runs for it to count as removed or added. */
    const val REMOVED_SHARE = 0.8

    /** R21: a side with more removed or added statements than this has no code moves. */
    const val MAX_STATEMENTS = 2_000

    /** Step 9: how many similarity scores are computed between two cancellation checks. */
    private const val CHECK_EVERY = 1_000

    /** One changed match and its changed runs, which the token comparison found; matches it did not compare take no part (R25). */
    class Input(val match: Match, val runs: List<RangePair>)

    /** One code move: its relationship, its source and target matches, and the moved statements' comparison. */
    class Found(
        val relationship: Relationship,
        val source: Match,
        val target: Match,
        val oldRange: TextRange,
        val newRange: TextRange,
        val content: Content,
        val changedRuns: List<RangePair>,
    )

    /** One removed or added statement with the tokens that are not whitespace or comments. */
    private class Side(
        val match: Match,
        val order: Int,
        val declaration: Declaration,
        val statementIndex: Int,
        val statement: Statement,
        val texts: List<String>,
    ) {
        val bag: Map<String, Int> by lazy {
            val bag = HashMap<String, Int>()
            for (i in 1 until texts.size) bag.merge(texts[i - 1] + PAIR_SEPARATOR + texts[i], 1, Int::plus)
            bag
        }
    }

    private class Paired(val old: Side, val new: Side, val score: Double)

    /**
     * The code moves among [inputs], given in old-side source order. [excludedOld] and [excludedNew] hold the code that
     * an extraction or an inlining already links, on each side (R10). [policy] is the language's whitespace policy.
     */
    fun detect(
        inputs: List<Input>,
        excludedOld: List<TextRange>,
        excludedNew: List<TextRange>,
        policy: WhitespacePolicy,
        cancellation: Cancellation,
    ): List<Found> {
        val removed = mutableListOf<Side>()
        val added = mutableListOf<Side>()
        for ((order, input) in inputs.withIndex()) {
            cancellation.checkCanceled()
            removed += changedStatements(input.match, order, input.match.old, input.runs.mapNotNull { it.old }, excludedOld)
            added += changedStatements(input.match, order, input.match.new, input.runs.mapNotNull { it.new }, excludedNew)
        }
        if (removed.isEmpty() || added.isEmpty()) return emptyList()
        if (removed.size > MAX_STATEMENTS || added.size > MAX_STATEMENTS) return emptyList()

        val pairs = assign(removed, added, cancellation)
        return runs(pairs).mapNotNull { run -> move(run, policy, cancellation) }
            .sortedWith(compareBy<Found>({ it.oldRange.start }, { it.newRange.start }))
    }

    /**
     * Step 2: the outermost statements of [declaration] whose tokens lie mostly in [changed], leaving out code that
     * overlaps [excluded]. A statement inside a chosen one is part of it (R7).
     */
    private fun changedStatements(
        match: Match,
        order: Int,
        declaration: Declaration,
        changed: List<TextRange>,
        excluded: List<TextRange>,
    ): List<Side> {
        if (changed.isEmpty() || declaration.statements.isEmpty()) return emptyList()
        if (declaration.ownTokenStarts.size != declaration.ownTokens.size) return emptyList()
        val runs = changed.sortedBy { it.start }
        val chosen = BooleanArray(declaration.statements.size)
        val sides = mutableListOf<Side>()
        for ((index, statement) in declaration.statements.withIndex()) {
            if (insideChosen(declaration.statements, index, chosen)) continue
            if (excluded.any { it.start < statement.range.end && statement.range.start < it.end }) continue
            val tokens = significant(declaration, statement.range)
            if (tokens.isEmpty()) continue
            val inRuns = tokens.count { (_, start) -> inside(runs, start) }
            if (inRuns < REMOVED_SHARE * tokens.size) continue
            chosen[index] = true
            sides += Side(match, order, declaration, index, statement, tokens.map { it.first.text })
        }
        return sides
    }

    private fun insideChosen(statements: List<Statement>, index: Int, chosen: BooleanArray): Boolean {
        var parent = statements[index].parent
        while (parent != null) {
            if (chosen[parent]) return true
            parent = statements[parent].parent
        }
        return false
    }

    /** Whether [offset] lies in one of [runs], which are sorted by start and do not overlap. */
    private fun inside(runs: List<TextRange>, offset: Int): Boolean {
        var low = 0
        var high = runs.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val run = runs[mid]
            when {
                offset < run.start -> high = mid - 1
                offset >= run.end -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** The own tokens of [declaration] in [range] that are not whitespace, comments or separators, with their starts. */
    private fun significant(declaration: Declaration, range: TextRange): List<Pair<Token, Int>> =
        tokensIn(declaration, range).filter { (token, _) -> token.kind == TokenKind.OTHER && token.text.isNotEmpty() }

    /** Every own token of [declaration] that starts in [range], with its start, in source order. */
    private fun tokensIn(declaration: Declaration, range: TextRange): List<Pair<Token, Int>> {
        val starts = declaration.ownTokenStarts
        val result = mutableListOf<Pair<Token, Int>>()
        for (i in starts.indices) {
            val start = starts[i]
            if (start >= range.start && start < range.end) result += declaration.ownTokens[i] to start
        }
        return result
    }

    /**
     * Steps 4 and 5: the similarity of every removed and added statement of the same kind, then the global assignment
     * of relationship matching over each group of statements linked by a score that can be a rival (R5, R6, R8).
     */
    private fun assign(removed: List<Side>, added: List<Side>, cancellation: Cancellation): List<Paired> {
        val scores = Array(removed.size) { DoubleArray(added.size) }
        var computed = 0
        val addedByKind = added.indices.groupBy { added[it].statement.kind }
        for (i in removed.indices) {
            for (j in addedByKind[removed[i].statement.kind].orEmpty()) {
                if (++computed % CHECK_EVERY == 0) cancellation.checkCanceled()
                scores[i][j] = similarity(removed[i], added[j])
            }
        }
        // A score at or below the floor less the margin can never be a rival of a chosen pair, so groups linked only
        // through such scores are independent.
        val link = SIMILARITY - ASSIGNMENT_MARGIN
        val parent = IntArray(removed.size + added.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        for (i in removed.indices) for (j in added.indices) {
            if (scores[i][j] > link) {
                val a = find(i)
                val b = find(removed.size + j)
                if (a != b) parent[maxOf(a, b)] = minOf(a, b)
            }
        }
        val groups = LinkedHashMap<Int, Pair<MutableList<Int>, MutableList<Int>>>()
        for (i in removed.indices) groups.getOrPut(find(i)) { mutableListOf<Int>() to mutableListOf() }.first += i
        for (j in added.indices) groups.getOrPut(find(removed.size + j)) { mutableListOf<Int>() to mutableListOf() }.second += j
        val pairs = mutableListOf<Paired>()
        for ((olds, news) in groups.values) {
            if (olds.isEmpty() || news.isEmpty()) continue
            if (olds.none { i -> news.any { j -> scores[i][j] >= SIMILARITY } }) continue
            val group = Array(olds.size) { a -> DoubleArray(news.size) { b -> scores[olds[a]][news[b]] } }
            for ((a, b) in GlobalAssignment.solve(group, SIMILARITY, null, cancellation)) {
                pairs += Paired(removed[olds[a]], added[news[b]], group[a][b])
            }
        }
        return pairs
    }

    /** The Dice coefficient of two statements' bags of adjacent token pairs; one-token statements compare by text. */
    private fun similarity(old: Side, new: Side): Double {
        if (old.texts.size < 2 || new.texts.size < 2) return if (old.texts == new.texts) 1.0 else 0.0
        return dice(old.bag, new.bag)
    }

    private fun dice(a: Map<String, Int>, b: Map<String, Int>): Double {
        val total = a.values.sum() + b.values.sum()
        if (total == 0) return 0.0
        return 2.0 * shared(a, b) / total
    }

    private fun shared(a: Map<String, Int>, b: Map<String, Int>): Int {
        val (small, large) = if (a.size <= b.size) a to b else b to a
        return small.entries.sumOf { (key, count) -> minOf(count, large[key] ?: 0) }
    }

    /**
     * Step 6: paired statements joined into maximal runs. Two pairs join when their old statements are next to each other
     * in one block of the same declaration and their new statements are too, in the same order (R2).
     */
    private fun runs(pairs: List<Paired>): List<List<Paired>> {
        val sorted = pairs.sortedWith(compareBy<Paired>({ it.old.order }, { it.old.statement.range.start }))
        val byOld = HashMap<Triple<Int, Int, Int>, MutableList<Paired>>()
        val runs = mutableListOf<MutableList<Paired>>()
        for (pair in sorted) {
            val previous = byOld[Triple(pair.old.order, pair.old.statement.block, pair.old.statement.index - 1)]
            val last = previous?.last()
            val joins = last != null &&
                last.new.match === pair.new.match &&
                last.new.statement.block == pair.new.statement.block &&
                last.new.statement.index + 1 == pair.new.statement.index
            val run = if (joins) previous!! else mutableListOf<Paired>().also { runs += it }
            run += pair
            byOld[Triple(pair.old.order, pair.old.statement.block, pair.old.statement.index)] = run
        }
        return runs
    }

    /** Steps 7 and 8: a run as a code move, or null when it is too short (R4). */
    private fun move(run: List<Paired>, policy: WhitespacePolicy, cancellation: Cancellation): Found? {
        cancellation.checkCanceled()
        if (run.sumOf { it.old.texts.size } < MIN_TOKENS) return null
        val source = run.first().old.match
        val target = run.first().new.match
        val oldRange = TextRange(run.first().old.statement.range.start, run.last().old.statement.range.end)
        val newRange = TextRange(run.first().new.statement.range.start, run.last().new.statement.range.end)
        val oldTokens = tokensIn(source.old, oldRange).filter { it.first.text.isNotEmpty() }
        val newTokens = tokensIn(target.new, newRange).filter { it.first.text.isNotEmpty() }
        val content = content(
            oldTokens.map { it.first }, indentation(source.old, oldRange.start),
            newTokens.map { it.first }, indentation(target.new, newRange.start),
            policy,
        )
        val changedRuns = if (content != Content.CHANGED) emptyList() else runCatching {
            ContentClassifier.changedRuns(
                oldTokens.map { it.first }, oldTokens.map { it.second },
                newTokens.map { it.first }, newTokens.map { it.second },
                // Python counts indentation as Scala 3 does, so its runs strip the move's common shift the same way.
                if (policy == WhitespacePolicy.INDENTATION_SIGNIFICANT) WhitespacePolicy.SCALA3_INDENTATION else policy,
                parseError = false,
                cancellation,
                oldOwn = listOf(oldRange),
                newOwn = listOf(newRange),
            )
        }.getOrElse { if (it is java.util.concurrent.CancellationException) throw it else null }.orEmpty()
        val oldBag = HashMap<String, Int>()
        val newBag = HashMap<String, Int>()
        fun bag(tokens: List<String>, into: HashMap<String, Int>) {
            for (i in 1 until tokens.size) into.merge(tokens[i - 1] + PAIR_SEPARATOR + tokens[i], 1, Int::plus)
        }
        bag(run.flatMap { it.old.texts }, oldBag)
        bag(run.flatMap { it.new.texts }, newBag)
        val total = maxOf(oldBag.values.sum(), newBag.values.sum())
        val relationship = Relationship(
            kinds = EnumSet.of(RelationshipKind.CODE_MOVE),
            old = listOf(RelationshipEndpoint(declarationSubject(source.old), source.old.range)),
            new = listOf(RelationshipEndpoint(declarationSubject(target.new), target.new.range)),
            confidence = if (content == Content.CHANGED) Confidence.MEDIUM else Confidence.HIGH,
            evidence = listOf(
                Evidence.Content(shared(oldBag, newBag), total, oldRange, newRange),
                Evidence.Score(run.minOf { it.score }),
            ),
        )
        return Found(relationship, source, target, oldRange, newRange, content, changedRuns)
    }

    /**
     * Step 8 (R11, R27): unchanged when the moved statements' text is the same, formatting-only when it is the same
     * under [policy] once each side's first-line indentation is removed from the indentation of its line breaks, and
     * changed otherwise.
     */
    private fun content(old: List<Token>, oldIndent: String, new: List<Token>, newIndent: String, policy: WhitespacePolicy): Content {
        val oldContent = old.filterNot { it.kind == TokenKind.SEPARATOR }
        val newContent = new.filterNot { it.kind == TokenKind.SEPARATOR }
        if (oldContent.map { it.text } == newContent.map { it.text }) return Content.UNCHANGED
        if (policy == WhitespacePolicy.SIGNIFICANT) return Content.CHANGED
        val same = runCatching {
            val a = ContentClassifier.normalize(oldContent, policy)
            val b = ContentClassifier.normalize(newContent, policy)
            if (policy.countsIndentation) {
                ContentClassifier.applyIndentShift(a, oldIndent) == ContentClassifier.applyIndentShift(b, newIndent)
            } else {
                a == b
            }
        }.getOrDefault(false)
        return if (same) Content.FORMATTING_ONLY else Content.CHANGED
    }

    /** The indentation of the line [offset] starts on: the whitespace before it in [declaration]'s own tokens. */
    private fun indentation(declaration: Declaration, offset: Int): String {
        val before = StringBuilder()
        val starts = declaration.ownTokenStarts
        var i = starts.indexOfLast { it < offset }
        while (i >= 0) {
            val token = declaration.ownTokens[i]
            if (token.kind != TokenKind.WHITESPACE && token.text.isNotEmpty()) break
            before.insert(0, token.text)
            i--
        }
        val text = before.toString()
        val lastBreak = maxOf(text.lastIndexOf('\n'), text.lastIndexOf('\r'))
        return if (lastBreak >= 0) text.substring(lastBreak + 1) else ""
    }

    private const val PAIR_SEPARATOR = "\u0000"
}
