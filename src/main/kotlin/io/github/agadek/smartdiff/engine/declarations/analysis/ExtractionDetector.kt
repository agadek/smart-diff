package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipEndpoint
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import java.util.EnumSet

/**
 * One extraction link as detection found it, with the shares its thresholds compare. Its [relationship] is the
 * engine-neutral form the result carries (relationships/02 R27); the shares stay for the corpus validation's near-miss
 * diagnostics (§8) and are not part of the result.
 */
internal class Extraction(
    val target: Declaration,
    val source: Match,
    /** The old-side range of the extracted code, inside the source's old range. */
    val origin: TextRange,
    /** The share of the target's body tokens found in the source's old tokens. */
    val covered: Double,
    /** The share of the tiled source tokens that were removed from the source. */
    val removed: Double,
    /** The target's body tokens the tiles covered. */
    val shared: Int,
    /** All of the target's body tokens. */
    val total: Int,
) {
    /** The [RelationshipKind.EXTRACT] relationship of this link (relationships/02 R18, R27). */
    val relationship: Relationship = Relationship(
        kinds = EnumSet.of(RelationshipKind.EXTRACT),
        old = listOf(RelationshipEndpoint(declarationSubject(source.old), source.old.range)),
        new = listOf(RelationshipEndpoint(declarationSubject(target), target.range)),
        confidence = Confidence.HIGH,
        evidence = listOf(
            Evidence.Content(shared, total, origin, target.range),
            Evidence.Score(covered),
        ),
    )
}

/** One inlining link as detection found it, with the shares its thresholds compare (relationships/05 R1, R2, R19). */
internal class Inlining(
    /** The removed declaration whose code was inlined. */
    val source: Declaration,
    /** The changed declaration that gained the code. */
    val target: Match,
    /** The old-side range of the inlined code, inside the removed declaration. */
    val origin: TextRange,
    /** The new-side range of the inlined code, inside the target. */
    val inlined: TextRange,
    /** The share of the removed body that the tiles covered. */
    val covered: Double,
    /** The share of the tiled target tokens that are new in the target. */
    val added: Double,
    /** The removed body tokens the tiles covered. */
    val shared: Int,
    /** All of the removed body's tokens. */
    val total: Int,
) {
    /** The [RelationshipKind.INLINE] relationship of this link (relationships/02 R35, R36). */
    val relationship: Relationship = Relationship(
        kinds = EnumSet.of(RelationshipKind.INLINE),
        old = listOf(RelationshipEndpoint(declarationSubject(source), source.range)),
        new = listOf(RelationshipEndpoint(declarationSubject(target.new), target.new.range)),
        confidence = Confidence.HIGH,
        evidence = listOf(
            Evidence.Content(shared, total, origin, inlined),
            Evidence.Score(covered),
        ),
    )
}

/**
 * Links added methods and functions to the code they were extracted from (extraction detection specification §4), and
 * removed methods and functions to the changed declarations their code was inlined into (relationships/05). It runs
 * after matching and classification and never changes what they decided: a link is extra information, never a match.
 *
 * Code is compared as significant tokens, `(type, text)` pairs without whitespace and comments, so reformatting moved
 * code does not stop detection. A source's **removed tokens** are the old tokens that an alignment of its old and new
 * tokens deletes; a target's **added tokens** are the new tokens that the reversed alignment inserts. An extraction
 * tiles an added target's body with a source's old tokens; an inlining tiles a removed body with a changed target's new
 * tokens. Greedy string tiling lets a match survive a few changed tokens.
 */
object ExtractionDetector {
    /** Bodies with fewer significant tokens match by chance. */
    const val MIN_TOKENS = 10

    /** The shortest run of tokens a tile may have. */
    const val MIN_TILE = 5

    /** The share of the moved body (an extraction target or a removed declaration) that must be tiled. */
    const val MIN_SHARE = 0.65

    /** The share of tiled tokens that must be removed (extraction) or added (inlining) on the other side. */
    const val MIN_REMOVED = 0.75

    /** Token pairs compared for one pass of one file at most; above it that pass is skipped for the file. */
    const val MAX_WORK = 20_000_000L

    /** The thresholds a link of either pass must pass; the validation lowers them to find near misses (§8). */
    data class Thresholds(val minShare: Double = MIN_SHARE, val minRemoved: Double = MIN_REMOVED)

    /** The share of a target's adjacent token pairs a source's removed tokens must have before the pair is tiled. */
    private const val MIN_PAIRS = 0.5

    private val TARGET_KINDS = setOf(DeclarationKind.METHOD, DeclarationKind.FUNCTION)

    /** The declarations that own code and can therefore lose or gain it. */
    private val CODE_OWNER_KINDS = setOf(
        DeclarationKind.METHOD, DeclarationKind.FUNCTION, DeclarationKind.CONSTRUCTOR,
        DeclarationKind.INITIALIZER, DeclarationKind.FIELD, DeclarationKind.PROPERTY,
    )

    /**
     * The extraction links among [matching]'s added declarations as relationships (relationships/02 R27);
     * [changed] tells which matches have changed content, the only ones that can be sources. Returns an empty list when
     * the work would exceed [maxWork].
     */
    fun detect(
        matching: Matching,
        changed: (Match) -> Boolean,
        cancellation: Cancellation,
        maxWork: Long = MAX_WORK,
        thresholds: Thresholds = Thresholds(),
    ): List<Relationship> = detectLinks(matching, changed, cancellation, maxWork, thresholds).map { it.relationship }

    /**
     * The inlining links from [matching]'s removed declarations to the changed declarations their code went into
     * (relationships/05 R1, R2, R19); [changed] tells which matches are targets. Returns an empty list when the work
     * would exceed [maxWork].
     */
    fun detectInlinings(
        matching: Matching,
        changed: (Match) -> Boolean,
        cancellation: Cancellation,
        maxWork: Long = MAX_WORK,
        thresholds: Thresholds = Thresholds(),
    ): List<Relationship> = detectInliningLinks(matching, changed, cancellation, maxWork, thresholds).map { it.relationship }

    /**
     * The links [detect] turns into relationships, with the shares it measured. The corpus validation uses them to list
     * the links lower thresholds would make (§8); the result never carries them.
     */
    internal fun detectLinks(
        matching: Matching,
        changed: (Match) -> Boolean,
        cancellation: Cancellation,
        maxWork: Long = MAX_WORK,
        thresholds: Thresholds = Thresholds(),
    ): List<Extraction> = detectLinks(matching, changed, Budget(maxWork, cancellation), thresholds)

    private fun detectLinks(
        matching: Matching,
        changed: (Match) -> Boolean,
        budget: Budget,
        thresholds: Thresholds,
    ): List<Extraction> {
        val keys = Keys()
        val targets = matching.added.filter { it.kind in TARGET_KINDS }.mapNotNull { declaration ->
            val body = declaration.body ?: return@mapNotNull null
            keys.significant(declaration) { it in body }?.takeIf { it.size >= MIN_TOKENS }?.let { Target(declaration, it) }
        }
        if (targets.isEmpty()) return emptyList()
        val candidates = matching.matches.filter { it.old.kind in CODE_OWNER_KINDS && changed(it) }
        if (candidates.isEmpty()) return emptyList()

        val found = try {
            val sources = candidates.mapNotNull { match ->
                val old = keys.significant(match.old) ?: return@mapNotNull null
                val new = keys.significant(match.new) ?: return@mapNotNull null
                Source(match, old, removedForExtraction(old.keys, new.keys, budget))
            }
            val pairs = targets.flatMap { target -> sources.filter { overlaps(target, it) }.map { target to it } }
            pairs.mapNotNull { (target, source) -> link(target, source, budget, thresholds) }
        } catch (e: OverBudget) {
            return emptyList()
        }
        // A target's sources come largest covered share first, then in old-side source order; a source's targets come
        // in new-side source order. The analysis reads the fields in these orders (relationships/02 R18).
        return found
            .groupBy { it.target }
            .values
            .sortedBy { links -> links.first().target.range.start }
            .flatMap { links -> links.sortedWith(compareByDescending<Extraction> { it.covered }.thenBy { it.source.old.range.start }) }
    }

    /**
     * The inlining links, with the shares they measured (relationships/05). The result is ordered by removed declaration
     * and then largest covered share first, then new-side target order, like the extraction links (relationships/02 R18).
     */
    internal fun detectInliningLinks(
        matching: Matching,
        changed: (Match) -> Boolean,
        cancellation: Cancellation,
        maxWork: Long = MAX_WORK,
        thresholds: Thresholds = Thresholds(),
    ): List<Inlining> = detectInliningLinks(matching, changed, Budget(maxWork, cancellation), thresholds)

    private fun detectInliningLinks(
        matching: Matching,
        changed: (Match) -> Boolean,
        budget: Budget,
        thresholds: Thresholds,
    ): List<Inlining> {
        val keys = Keys()
        val sources = matching.removed.filter { it.kind in TARGET_KINDS }.mapNotNull { declaration ->
            val body = declaration.body ?: return@mapNotNull null
            keys.significant(declaration) { it in body }?.takeIf { it.size >= MIN_TOKENS }?.let { Removed(declaration, it) }
        }
        if (sources.isEmpty()) return emptyList()
        val candidates = matching.matches.filter { it.new.kind in CODE_OWNER_KINDS && changed(it) }
        if (candidates.isEmpty()) return emptyList()

        val found = try {
            val targets = candidates.mapNotNull { match ->
                val old = keys.significant(match.old) ?: return@mapNotNull null
                val new = keys.significant(match.new) ?: return@mapNotNull null
                val added = removed(new.keys, old.keys, budget)
                if (added.none { it }) return@mapNotNull null
                Changed(match, new, added)
            }
            val pairs = sources.flatMap { source -> targets.filter { overlaps(source, it) }.map { source to it } }
            pairs.mapNotNull { (source, target) -> inline(source, target, budget, thresholds) }
        } catch (e: OverBudget) {
            return emptyList()
        }
        return found
            .groupBy { it.source }
            .values
            .sortedBy { links -> links.first().source.range.start }
            .flatMap { links -> links.sortedWith(compareByDescending<Inlining> { it.covered }.thenBy { it.target.new.range.start }) }
    }

    private class Target(val declaration: Declaration, val tokens: Significant) {
        val pairs: Map<Long, Int> by lazy { pairs(tokens.keys, IntArray(tokens.size) { it }) }
    }

    private class Source(val match: Match, val old: Significant, val removed: BooleanArray) {
        val removedPairs: Map<Long, Int> by lazy { pairs(old.keys, old.keys.indices.filter { removed[it] }.toIntArray()) }
    }

    private class Removed(val declaration: Declaration, val tokens: Significant) {
        val pairs: Map<Long, Int> by lazy { pairs(tokens.keys, IntArray(tokens.size) { it }) }
    }

    private class Changed(val match: Match, val tokens: Significant, val added: BooleanArray) {
        val addedPairs: Map<Long, Int> by lazy { pairs(tokens.keys, tokens.keys.indices.filter { added[it] }.toIntArray()) }
    }

    /** §4.6: whether the source's removed tokens have enough of the target's adjacent token pairs to be worth tiling. */
    private fun overlaps(target: Target, source: Source): Boolean {
        val wanted = target.pairs
        val total = wanted.values.sum()
        if (total == 0) return false
        val shared = wanted.entries.sumOf { (pair, count) -> minOf(count, source.removedPairs[pair] ?: 0) }
        return shared >= MIN_PAIRS * total
    }

    /** The reverse pre-check: whether the target's added tokens have enough of the removed body's adjacent pairs. */
    private fun overlaps(source: Removed, target: Changed): Boolean {
        val wanted = source.pairs
        val total = wanted.values.sum()
        if (total == 0) return false
        val shared = wanted.entries.sumOf { (pair, count) -> minOf(count, target.addedPairs[pair] ?: 0) }
        return shared >= MIN_PAIRS * total
    }

    /** §4.4: the extraction of [target] from [source], if the tiles cover enough of it and come from removed code. */
    private fun link(target: Target, source: Source, budget: Budget, thresholds: Thresholds): Extraction? {
        val tiles = tile(target.tokens.keys, source.old.keys, budget)
        val tiled = tiles.sumOf { it.length }
        if (tiled == 0 || tiled < thresholds.minShare * target.tokens.size) return null
        val removed = tiles.sumOf { tile -> (tile.source until tile.source + tile.length).count { source.removed[it] } }
        if (removed < thresholds.minRemoved * tiled) return null
        val first = tiles.minOf { it.source }
        val last = tiles.maxOf { it.source + it.length }
        if (last - first > 2 * tiled) return null
        val origin = TextRange(source.old.starts[first], source.old.ends[last - 1])
        return Extraction(
            target = target.declaration,
            source = source.match,
            origin = origin,
            covered = tiled.toDouble() / target.tokens.size,
            removed = removed.toDouble() / tiled,
            shared = tiled,
            total = target.tokens.size,
        )
    }

    /** §4.4 swapped: the inlining of [source]'s body into [target], if the tiles cover enough and are new there. */
    private fun inline(source: Removed, target: Changed, budget: Budget, thresholds: Thresholds): Inlining? {
        val tiles = tile(source.tokens.keys, target.tokens.keys, budget)
        val tiled = tiles.sumOf { it.length }
        if (tiled == 0 || tiled < thresholds.minShare * source.tokens.size) return null
        val added = tiles.sumOf { tile -> (tile.source until tile.source + tile.length).count { target.added[it] } }
        if (added < thresholds.minRemoved * tiled) return null
        val firstTarget = tiles.minOf { it.target }
        val lastTarget = tiles.maxOf { it.target + it.length }
        val firstSource = tiles.minOf { it.source }
        val lastSource = tiles.maxOf { it.source + it.length }
        if (lastSource - firstSource > 2 * tiled) return null
        val origin = TextRange(source.tokens.starts[firstTarget], source.tokens.ends[lastTarget - 1])
        val inlined = TextRange(target.tokens.starts[firstSource], target.tokens.ends[lastSource - 1])
        return Inlining(
            source = source.declaration,
            target = target.match,
            origin = origin,
            inlined = inlined,
            covered = tiled.toDouble() / source.tokens.size,
            added = added.toDouble() / tiled,
            shared = tiled,
            total = source.tokens.size,
        )
    }

    private class Tile(val target: Int, val source: Int, val length: Int)

    /** Tiles taken first: longer, then earlier in the target, then earlier in the source. */
    private val TILE_ORDER = compareByDescending<Tile> { it.length }.thenBy { it.target }.thenBy { it.source }

    /**
     * Greedy string tiling: takes the longest common runs of tokens not yet tiled on either side, down to [MIN_TILE]
     * tokens. The maximal common runs are found through an index of the source's runs of [MIN_TILE] tokens, so the
     * work grows with the tokens and the runs they share rather than with the product of the token counts. A run that
     * overlaps a taken tile goes back to the queue as its parts that are still free.
     */
    private fun tile(target: IntArray, source: IntArray, budget: Budget): List<Tile> {
        if (target.size < MIN_TILE || source.size < MIN_TILE) return emptyList()
        budget.spend(source.size.toLong())
        val index = HashMap<Long, MutableList<Int>>()
        for (j in 0..source.size - MIN_TILE) {
            val starts = index.getOrPut(gram(source, j), ::mutableListOf)
            if (starts.size < MAX_STARTS) starts += j
        }
        val queue = java.util.PriorityQueue(TILE_ORDER)
        for (i in 0..target.size - MIN_TILE) {
            val starts = index[gram(target, i)] ?: continue
            budget.spend(starts.size.toLong())
            for (j in starts) {
                // A run that extends to the left was found from its first token already.
                if (i > 0 && j > 0 && target[i - 1] == source[j - 1]) continue
                var length = 0
                while (i + length < target.size && j + length < source.size && target[i + length] == source[j + length]) length++
                budget.spend(length.toLong())
                if (length >= MIN_TILE) queue += Tile(i, j, length)
            }
        }
        val tiles = mutableListOf<Tile>()
        val inTarget = BooleanArray(target.size)
        val inSource = BooleanArray(source.size)
        while (queue.isNotEmpty()) {
            val run = queue.poll()
            budget.spend(run.length.toLong())
            var k = 0
            var free = 0
            while (k <= run.length) {
                val taken = k == run.length || inTarget[run.target + k] || inSource[run.source + k]
                if (!taken) {
                    free++
                } else {
                    if (free == run.length) {
                        for (t in 0 until run.length) {
                            inTarget[run.target + t] = true
                            inSource[run.source + t] = true
                        }
                        tiles += run
                    } else if (free >= MIN_TILE) {
                        queue += Tile(run.target + k - free, run.source + k - free, free)
                    }
                    free = 0
                }
                k++
            }
        }
        return tiles
    }

    /** At most this many places in the source are kept for one run of [MIN_TILE] tokens, against repetitive code. */
    private const val MAX_STARTS = 64

    /** A hash of the [MIN_TILE] tokens of [keys] from [start]; runs with the same hash are compared token by token. */
    private fun gram(keys: IntArray, start: Int): Long {
        var hash = 0L
        for (k in start until start + MIN_TILE) hash = hash * 1_000_003 + keys[k]
        return hash
    }

    /**
     * §4.3: which of [old] an alignment with [new] deletes, by Myers' diff in linear space: common prefix and suffix
     * first, then split at a point on an optimal path and align both halves.
     */
    internal fun removed(old: IntArray, new: IntArray, budget: Budget): BooleanArray = alignment(old, new, budget).first

    /** The alignment's deleted tokens and, for each kept old token, the new index it is aligned to (or -1). */
    private fun alignment(old: IntArray, new: IntArray, budget: Budget): Pair<BooleanArray, IntArray> {
        val deleted = BooleanArray(old.size)
        val alignedNew = IntArray(old.size) { -1 }
        fun align(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) {
            var a = oldStart
            var b = newStart
            var c = oldEnd
            var d = newEnd
            while (a < c && b < d && old[a] == new[b]) { alignedNew[a] = b; a++; b++ }
            while (a < c && b < d && old[c - 1] == new[d - 1]) { c--; d--; alignedNew[c] = d }
            if (a == c) return
            if (b == d) {
                for (i in a until c) deleted[i] = true
                return
            }
            val split = split(old, a, c, new, b, d, budget)
            if (split == null) {
                for (i in a until c) deleted[i] = true
                return
            }
            align(a, split.first, b, split.second)
            align(split.first, c, split.second, d)
        }
        align(0, old.size, 0, new.size)
        return deleted to alignedNew
    }

    /**
     * R17: for an extraction source, a kept old token stays kept only in a diagonal run of at least [MIN_TILE] tokens;
     * shorter kept runs count as removed too.
     */
    private fun removedForExtraction(old: IntArray, new: IntArray, budget: Budget): BooleanArray {
        val (deleted, alignedNew) = alignment(old, new, budget)
        var i = 0
        while (i < old.size) {
            if (deleted[i]) { i++; continue }
            val start = i
            var j = i + 1
            if (alignedNew[i] >= 0) {
                while (j < old.size && !deleted[j] && alignedNew[j] == alignedNew[j - 1] + 1) j++
            }
            // A kept token without an alignment, or in a shorter run, counts as removed too.
            if (j - start < MIN_TILE) {
                for (k in start until j) deleted[k] = true
            }
            i = j
        }
        return deleted
    }

    /**
     * A point `(x, y)` on an optimal edit path from `(a0, b0)` to `(a1, b1)`, where the forward and reverse searches
     * meet; null when the ranges have nothing in common. Both ranges are non-empty and differ at both ends.
     */
    private fun split(a: IntArray, a0: Int, a1: Int, b: IntArray, b0: Int, b1: Int, budget: Budget): Pair<Int, Int>? {
        val n = a1 - a0
        val m = b1 - b0
        val maxD = (n + m + 1) / 2
        val offset = maxD
        val length = 2 * maxD + 2
        val forward = IntArray(length) { -1 }
        val reverse = IntArray(length) { -1 }
        forward[offset + 1] = 0
        reverse[offset + 1] = 0
        val delta = n - m
        // The searches meet on the forward pass when the difference in lengths is odd.
        val front = delta % 2 != 0
        var k1Start = 0
        var k1End = 0
        var k2Start = 0
        var k2End = 0
        for (d in 0 until maxD) {
            budget.spend(2L * (d + 1))
            var k1 = -d + k1Start
            while (k1 <= d - k1End) {
                val i = offset + k1
                var x = if (k1 == -d || (k1 != d && forward[i - 1] < forward[i + 1])) forward[i + 1] else forward[i - 1] + 1
                var y = x - k1
                while (x < n && y < m && a[a0 + x] == b[b0 + y]) { x++; y++ }
                forward[i] = x
                if (x > n) {
                    k1End += 2
                } else if (y > m) {
                    k1Start += 2
                } else if (front) {
                    val j = offset + delta - k1
                    if (j in 0 until length && reverse[j] != -1 && x >= n - reverse[j]) return a0 + x to b0 + y
                }
                k1 += 2
            }
            var k2 = -d + k2Start
            while (k2 <= d - k2End) {
                val j = offset + k2
                var x = if (k2 == -d || (k2 != d && reverse[j - 1] < reverse[j + 1])) reverse[j + 1] else reverse[j - 1] + 1
                var y = x - k2
                while (x < n && y < m && a[a1 - x - 1] == b[b1 - y - 1]) { x++; y++ }
                reverse[j] = x
                if (x > n) {
                    k2End += 2
                } else if (y > m) {
                    k2Start += 2
                } else if (!front) {
                    val i = offset + delta - k2
                    if (i in 0 until length && forward[i] != -1) {
                        val x1 = forward[i]
                        val y1 = offset + x1 - i
                        if (x1 >= n - x) return a0 + x1 to b0 + y1
                    }
                }
                k2 += 2
            }
        }
        return null
    }

    /** Adjacent pairs of [keys] at [indices], as a bag. */
    private fun pairs(keys: IntArray, indices: IntArray): Map<Long, Int> {
        val bag = HashMap<Long, Int>()
        for (k in 1 until indices.size) bag.merge(keys[indices[k - 1]].toLong() shl 32 or keys[indices[k]].toLong(), 1, Int::plus)
        return bag
    }

    /** Significant tokens: one key per `(type, text)`, and where each token starts and ends. */
    class Significant(val keys: IntArray, val starts: IntArray, val ends: IntArray) {
        val size: Int get() = keys.size
    }

    /** Numbers `(type, text)` pairs, so tokens compare as integers. */
    private class Keys {
        private val ids = HashMap<Pair<String, String>, Int>()

        /** The significant own tokens of [declaration] that start where [include] says; null without token offsets. */
        fun significant(declaration: Declaration, include: (Int) -> Boolean = { true }): Significant? {
            val tokens = declaration.ownTokens
            val starts = declaration.ownTokenStarts
            if (starts.isEmpty() || starts.size != tokens.size) return null
            val keys = mutableListOf<Int>()
            val from = mutableListOf<Int>()
            val to = mutableListOf<Int>()
            for ((i, token) in tokens.withIndex()) {
                if (token.kind != TokenKind.OTHER || !include(starts[i])) continue
                keys += ids.getOrPut(token.type to token.text) { ids.size }
                from += starts[i]
                to += starts[i] + token.text.length
            }
            return Significant(keys.toIntArray(), from.toIntArray(), to.toIntArray())
        }
    }

    /** Counts the work of one file and checks cancellation as it goes. */
    internal class Budget(private val limit: Long, private val cancellation: Cancellation) {
        private var spent = 0L

        fun spend(work: Long) {
            spent += work
            check(0)
        }

        /** Throws [OverBudget] when [more] work would go over the limit. */
        fun check(more: Long) {
            cancellation.checkCanceled()
            if (spent + more > limit) throw OverBudget()
        }
    }

    private class OverBudget : RuntimeException(null, null, false, false)
}
