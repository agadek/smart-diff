package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation

/**
 * Myers' difference algorithm, linear-space variant, over two sequences (declarations/10 details, the token comparison
 * step 4). It finds a longest common subsequence as the list of matched index pairs, in order on both sides, or null when
 * the edit distance, removed plus added elements, passes [maxEdits].
 *
 * The result is deterministic: the same inputs always give the same pairs. [cancellation] is checked every
 * [CHECK_EVERY] steps of the algorithm.
 */
internal class TokenDiff<T>(
    private val a: List<T>,
    private val b: List<T>,
    private val equal: (T, T) -> Boolean,
    private val cancellation: Cancellation,
) {
    private val pairs = ArrayList<IntArray>()
    private var steps = 0L

    /** The matched pairs `[oldIndex, newIndex]` in order, or null when the edit distance passes [maxEdits]. */
    fun matches(maxEdits: Int): List<IntArray>? {
        val distance = distance(maxEdits) ?: return null
        if (distance > maxEdits) return null
        lcs(0, a.size, 0, b.size)
        return pairs
    }

    /** The edit distance after the common prefix and suffix, or null when it passes [maxEdits]; found by one middle snake. */
    private fun distance(maxEdits: Int): Int? {
        var aLo = 0
        var bLo = 0
        var aHi = a.size
        var bHi = b.size
        while (aLo < aHi && bLo < bHi && equal(a[aLo], b[bLo])) { aLo++; bLo++ }
        while (aHi > aLo && bHi > bLo && equal(a[aHi - 1], b[bHi - 1])) { aHi--; bHi-- }
        if (aLo == aHi || bLo == bHi) return (aHi - aLo) + (bHi - bLo)
        // The middle snake is found at step d of an edit script of length 2d - 1 or 2d; stop once d alone passes the limit.
        val snake = middleSnake(aLo, aHi, bLo, bHi, maxD = maxEdits / 2 + 1) ?: return null
        return snake[4]
    }

    private fun step() {
        if (++steps % CHECK_EVERY == 0L) cancellation.checkCanceled()
    }

    private fun lcs(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int) {
        var aLo = aStart
        var bLo = bStart
        var aHi = aEnd
        var bHi = bEnd
        while (aLo < aHi && bLo < bHi && equal(a[aLo], b[bLo])) {
            pairs += intArrayOf(aLo, bLo)
            aLo++
            bLo++
            step()
        }
        var suffix = 0
        while (aHi > aLo && bHi > bLo && equal(a[aHi - 1], b[bHi - 1])) {
            aHi--
            bHi--
            suffix++
            step()
        }
        if (aLo < aHi && bLo < bHi) {
            val snake = middleSnake(aLo, aHi, bLo, bHi, maxD = Int.MAX_VALUE)!!
            val (x, y, u, v) = snake
            lcs(aLo, x, bLo, y)
            for (i in 0 until u - x) pairs += intArrayOf(x + i, y + i)
            lcs(u, aHi, v, bHi)
        }
        for (i in 0 until suffix) pairs += intArrayOf(aHi + i, bHi + i)
    }

    /**
     * The middle snake of `a[aLo, aHi)` and `b[bLo, bHi)` as `[x, y, u, v, distance]`: absolute start and end of the
     * snake and the length of the shortest edit script. Null when the search passes [maxD] steps.
     */
    private fun middleSnake(aLo: Int, aHi: Int, bLo: Int, bHi: Int, maxD: Int): IntArray? {
        val n = aHi - aLo
        val m = bHi - bLo
        val delta = n - m
        val odd = delta and 1 != 0
        val max = (n + m + 1) / 2
        val offset = max + 1
        val vf = IntArray(2 * max + 3)
        val vb = IntArray(2 * max + 3)
        for (d in 0..max) {
            if (d > maxD) return null
            // Forward paths.
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && vf[offset + k - 1] < vf[offset + k + 1])) vf[offset + k + 1] else vf[offset + k - 1] + 1
                var y = x - k
                val x0 = x
                val y0 = y
                while (x < n && y < m && equal(a[aLo + x], b[bLo + y])) { x++; y++; step() }
                step()
                vf[offset + k] = x
                val kb = delta - k
                if (odd && kb >= -(d - 1) && kb <= d - 1 && x + vb[offset + kb] >= n) {
                    return intArrayOf(aLo + x0, bLo + y0, aLo + x, bLo + y, 2 * d - 1)
                }
                k += 2
            }
            // Backward paths, in coordinates counted from the ends.
            var kb = -d
            while (kb <= d) {
                var x = if (kb == -d || (kb != d && vb[offset + kb - 1] < vb[offset + kb + 1])) vb[offset + kb + 1] else vb[offset + kb - 1] + 1
                var y = x - kb
                val x0 = x
                val y0 = y
                while (x < n && y < m && equal(a[aHi - 1 - x], b[bHi - 1 - y])) { x++; y++; step() }
                step()
                vb[offset + kb] = x
                val kf = delta - kb
                if (!odd && kf >= -d && kf <= d && x + vf[offset + kf] >= n) {
                    return intArrayOf(aHi - x, bHi - y, aHi - x0, bHi - y0, 2 * d)
                }
                kb += 2
            }
        }
        error("no middle snake")
    }

    companion object {
        /** How many steps of the algorithm run between two cancellation checks (declarations/10 details, step 7). */
        const val CHECK_EVERY = 1_000L
    }
}
