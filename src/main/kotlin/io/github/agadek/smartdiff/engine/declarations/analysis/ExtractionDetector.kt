package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import java.util.IdentityHashMap

/** An added declaration whose body is mostly code that [source]'s old side had and its new side no longer has. */
class Extraction(
    val target: Declaration,
    val source: Match,
    /** The old-side range of the extracted code, inside the source's old range. */
    val origin: TextRange,
    /** The share of the target's body tokens found in the source's old tokens. */
    val covered: Double,
    /** The share of the tiled source tokens that were removed from the source. */
    val removed: Double,
)

/** The extractions of one comparison, from both ends. */
class Extractions(
    /** For each added declaration, its sources: largest covered share first, then in old-side source order. */
    val ofTarget: Map<Declaration, List<Extraction>>,
    /** For each source's old declaration, the extractions from it, in new-side source order. */
    val ofSource: Map<Declaration, List<Extraction>>,
) {
    companion object {
        val NONE = Extractions(emptyMap(), emptyMap())
    }
}

/**
 * Links added methods and functions to the code they were extracted from (extraction detection specification §4). It
 * runs after matching and classification and never changes what they decided: a link is extra information on an added
 * declaration, never a match.
 *
 * Code is compared as significant tokens, `(type, text)` pairs without whitespace and comments, so reformatting the
 * extracted code does not stop detection. A source's **removed tokens** are the old tokens that an alignment of its old
 * and new tokens deletes; code the new side still has never counts. A target's body is tiled with a source's old
 * tokens by greedy string tiling, which lets a match survive a few changed tokens.
 */
object ExtractionDetector {
    /** Bodies with fewer significant tokens match by chance. */
    const val MIN_TOKENS = 10

    /** The shortest run of tokens a tile may have. */
    const val MIN_TILE = 5

    /** The share of the target's body that must be tiled. */
    const val MIN_SHARE = 0.75

    /** The share of the tiled source tokens that must have been removed from the source. */
    const val MIN_REMOVED = 0.75

    /** Token pairs compared for one file at most; above it detection is skipped for the file. */
    const val MAX_WORK = 20_000_000L

    /** The thresholds a link must pass; the validation lowers them to find near misses (§8). */
    data class Thresholds(val minShare: Double = MIN_SHARE, val minRemoved: Double = MIN_REMOVED)

    /** The share of a target's adjacent token pairs a source's removed tokens must have before the pair is tiled. */
    private const val MIN_PAIRS = 0.5

    private val TARGET_KINDS = setOf(DeclarationKind.METHOD, DeclarationKind.FUNCTION)
    private val SOURCE_KINDS = setOf(
        DeclarationKind.METHOD, DeclarationKind.FUNCTION, DeclarationKind.CONSTRUCTOR,
        DeclarationKind.INITIALIZER, DeclarationKind.FIELD, DeclarationKind.PROPERTY,
    )

    /**
     * The extractions among [matching]'s added declarations; [changed] tells which matches have changed content, the
     * only ones that can be sources. Returns [Extractions.NONE] when the work would exceed [maxWork].
     */
    fun detect(
        matching: Matching,
        changed: (Match) -> Boolean,
        cancellation: Cancellation,
        maxWork: Long = MAX_WORK,
        thresholds: Thresholds = Thresholds(),
    ): Extractions {
        val keys = Keys()
        val targets = matching.added.filter { it.kind in TARGET_KINDS }.mapNotNull { declaration ->
            val body = declaration.body ?: return@mapNotNull null
            keys.significant(declaration) { it in body }?.takeIf { it.size >= MIN_TOKENS }?.let { Target(declaration, it) }
        }
        if (targets.isEmpty()) return Extractions.NONE
        val candidates = matching.matches.filter { it.old.kind in SOURCE_KINDS && changed(it) }
        if (candidates.isEmpty()) return Extractions.NONE

        val budget = Budget(maxWork, cancellation)
        val found = try {
            val sources = candidates.mapNotNull { match ->
                val old = keys.significant(match.old) ?: return@mapNotNull null
                val new = keys.significant(match.new) ?: return@mapNotNull null
                Source(match, old, removed(old.keys, new.keys, budget))
            }
            val pairs = targets.flatMap { target -> sources.filter { overlaps(target, it) }.map { target to it } }
            budget.check(pairs.sumOf { (target, source) -> target.tokens.size.toLong() * source.old.size })
            pairs.mapNotNull { (target, source) -> link(target, source, budget, thresholds) }
        } catch (e: OverBudget) {
            return Extractions.NONE
        }
        if (found.isEmpty()) return Extractions.NONE

        val ofTarget = IdentityHashMap<Declaration, List<Extraction>>()
        found.groupBy { it.target }.forEach { (target, links) ->
            ofTarget[target] = links.sortedWith(compareByDescending<Extraction> { it.covered }.thenBy { it.source.old.range.start })
        }
        val ofSource = IdentityHashMap<Declaration, List<Extraction>>()
        found.groupBy { it.source.old }.forEach { (old, links) -> ofSource[old] = links.sortedBy { it.target.range.start } }
        return Extractions(ofTarget, ofSource)
    }

    private class Target(val declaration: Declaration, val tokens: Significant) {
        val pairs: Map<Long, Int> by lazy { pairs(tokens.keys, IntArray(tokens.size) { it }) }
    }

    private class Source(val match: Match, val old: Significant, val removed: BooleanArray) {
        val removedPairs: Map<Long, Int> by lazy { pairs(old.keys, old.keys.indices.filter { removed[it] }.toIntArray()) }
    }

    /** §4.6: whether the source's removed tokens have enough of the target's adjacent token pairs to be worth tiling. */
    private fun overlaps(target: Target, source: Source): Boolean {
        val wanted = target.pairs
        val total = wanted.values.sum()
        if (total == 0) return false
        val shared = wanted.entries.sumOf { (pair, count) -> minOf(count, source.removedPairs[pair] ?: 0) }
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
        return Extraction(target.declaration, source.match, origin, tiled.toDouble() / target.tokens.size, removed.toDouble() / tiled)
    }

    private class Tile(val target: Int, val source: Int, val length: Int)

    /**
     * Greedy string tiling: repeatedly takes the longest common runs of tokens not yet tiled on either side, down to
     * [MIN_TILE] tokens. Runs of the same length are taken in target order, skipping those that overlap a taken one.
     */
    private fun tile(target: IntArray, source: IntArray, budget: Budget): List<Tile> {
        val tiles = mutableListOf<Tile>()
        val inTarget = BooleanArray(target.size)
        val inSource = BooleanArray(source.size)
        while (true) {
            budget.spend(target.size.toLong() * source.size)
            var longest = MIN_TILE - 1
            val runs = mutableListOf<Tile>()
            var previous = IntArray(source.size + 1)
            var current = IntArray(source.size + 1)
            for (i in 1..target.size) {
                for (j in 1..source.size) {
                    val length = if (!inTarget[i - 1] && !inSource[j - 1] && target[i - 1] == source[j - 1]) previous[j - 1] + 1 else 0
                    current[j] = length
                    if (length > longest) {
                        longest = length
                        runs.clear()
                    }
                    if (length == longest) runs += Tile(i - length, j - length, length)
                }
                val swap = previous
                previous = current
                current = swap
            }
            if (runs.isEmpty()) return tiles
            for (run in runs) {
                val free = (run.target until run.target + run.length).none { inTarget[it] } &&
                    (run.source until run.source + run.length).none { inSource[it] }
                if (!free) continue
                for (k in 0 until run.length) {
                    inTarget[run.target + k] = true
                    inSource[run.source + k] = true
                }
                tiles += run
            }
        }
    }

    /**
     * §4.3: which of [old] an alignment with [new] deletes, by Myers' diff in linear space: common prefix and suffix
     * first, then split at a point on an optimal path and align both halves.
     */
    internal fun removed(old: IntArray, new: IntArray, budget: Budget): BooleanArray {
        val deleted = BooleanArray(old.size)
        fun align(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) {
            var a = oldStart
            var b = newStart
            var c = oldEnd
            var d = newEnd
            while (a < c && b < d && old[a] == new[b]) { a++; b++ }
            while (a < c && b < d && old[c - 1] == new[d - 1]) { c--; d-- }
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
