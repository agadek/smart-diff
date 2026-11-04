package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import java.math.BigInteger

/** How far ahead of every rival a pair must be (relationships/03, `MARGIN`). */
internal const val ASSIGNMENT_MARGIN = 0.1

/** How alike the names of a pair with close rivals must be to resolve a near-tie. */
internal const val ASSIGNMENT_TIEBREAK_MIN = 0.5

/** How much better than every close rival's name a tie-break pair's name must be. */
internal const val ASSIGNMENT_TIEBREAK_MARGIN = 0.2

/**
 * Maximum-weight bipartite matching over one candidate group (relationships/03, `Assignment`).
 *
 * The total of an assignment is its scores added. Of assignments with equal totals, the one whose pairs
 * `(old index, new index)`, sorted by old and then new index, is lexicographically smallest wins; that is the old side's
 * source order, then the new side's (R11). A pair at the group's floor is only taken when every rival, as the details
 * define one, leaves at least [ASSIGNMENT_MARGIN] between it and the pair; a near-tie can still pass through the name
 * tie-break. Edges that fail are dropped and the group is solved again, as the details allow.
 */
internal object GlobalAssignment {

    /**
     * The pairs of [scores] indices, old first, that the assignment takes. [scores] holds every possible edge, below the
     * [floor] included, so a rival's raw score is available. [tiebreak] holds the name similarity of each edge, or null
     * when the group has no name tie-break (an overload group).
     */
    fun solve(
        scores: Array<DoubleArray>,
        floor: Double,
        tiebreak: Array<DoubleArray>?,
        cancellation: Cancellation,
    ): List<Pair<Int, Int>> {
        val olds = scores.size
        val news = if (olds == 0) 0 else scores[0].size
        if (olds == 0 || news == 0) return emptyList()
        val allowed = Array(olds) { i -> BooleanArray(news) { j -> scores[i][j] >= floor } }
        if (allowed.all { row -> row.none { it } }) return emptyList()
        while (true) {
            val partner = maximum(scores, allowed, cancellation)
            val unpairedOld = BooleanArray(olds) { partner[it] < 0 }
            val pairedNew = BooleanArray(news)
            for (j in partner) if (j >= 0) pairedNew[j] = true
            val failing = mutableListOf<Pair<Int, Int>>()
            for (i in 0 until olds) {
                val j = partner[i]
                if (j < 0) continue
                if (!passes(i, j, scores, unpairedOld, pairedNew, tiebreak)) failing += i to j
            }
            if (failing.isEmpty()) {
                return (0 until olds).filter { partner[it] >= 0 }.map { it to partner[it] }
            }
            for ((i, j) in failing) allowed[i][j] = false
            if (allowed.all { row -> row.none { it } }) return emptyList()
        }
    }

    /** Whether the chosen edge [i], [j] keeps [ASSIGNMENT_MARGIN] from every rival, or wins the name tie-break. */
    private fun passes(
        i: Int,
        j: Int,
        scores: Array<DoubleArray>,
        unpairedOld: BooleanArray,
        pairedNew: BooleanArray,
        tiebreak: Array<DoubleArray>?,
    ): Boolean {
        val close = scores[i][j] - ASSIGNMENT_MARGIN
        var rival = false
        for (other in unpairedOld.indices) {
            if (other != i && unpairedOld[other] && scores[other][j] > close) {
                rival = true
                break
            }
        }
        if (!rival) {
            for (other in pairedNew.indices) {
                if (other != j && !pairedNew[other] && scores[i][other] > close) {
                    rival = true
                    break
                }
            }
        }
        if (!rival) return true
        val names = tiebreak ?: return false
        val own = names[i][j]
        if (own < ASSIGNMENT_TIEBREAK_MIN) return false
        for (other in unpairedOld.indices) {
            if (other != i && unpairedOld[other] && scores[other][j] > close && names[other][j] > own - ASSIGNMENT_TIEBREAK_MARGIN) {
                return false
            }
        }
        for (other in pairedNew.indices) {
            if (other != j && !pairedNew[other] && scores[i][other] > close && names[i][other] > own - ASSIGNMENT_TIEBREAK_MARGIN) {
                return false
            }
        }
        return true
    }

    /**
     * The maximum-weight matching within [allowed]. Edges outside [allowed] are blocked; every declaration may stay
     * unpaired through a zero-weight dummy. The Hungarian algorithm runs on the smaller side, with a lexicographic pair
     * of score and pair-order bit vector as its cost, so equal totals follow R11.
     */
    private fun maximum(scores: Array<DoubleArray>, allowed: Array<BooleanArray>, cancellation: Cancellation): IntArray {
        val olds = scores.size
        val news = scores[0].size
        val transposed = olds > news
        val rows = if (transposed) news else olds
        val realColumns = if (transposed) olds else news
        val totalColumns = realColumns + rows
        val totalEdges = olds * news
        val costs = Array(rows) { row ->
            Array(totalColumns) { column ->
                if (column >= realColumns) {
                    Cost.ZERO
                } else {
                    val i = if (transposed) column else row
                    val j = if (transposed) row else column
                    if (allowed[i][j]) {
                        val bit = BigInteger.ONE.shiftLeft(totalEdges - 1 - (i * news + j))
                        Cost(-scores[i][j], bit.negate())
                    } else {
                        Cost.BLOCKED
                    }
                }
            }
        }
        val assignment = hungarian(costs, cancellation)
        val partner = IntArray(olds) { -1 }
        for (row in 0 until rows) {
            val column = assignment[row]
            if (column < 0 || column >= realColumns) continue
            val i = if (transposed) column else row
            val j = if (transposed) row else column
            partner[i] = j
        }
        return partner
    }

    /**
     * The minimum-cost perfect matching of a [rows] by [columns] matrix with [rows] at most [columns], as the column of
     * each row. The last [rows] columns are dummies, so every row has a zero option.
     */
    private fun hungarian(costs: Array<Array<Cost>>, cancellation: Cancellation): IntArray {
        val rows = costs.size
        val columns = costs[0].size
        val u = Array(rows + 1) { Cost.ZERO }
        val v = Array(columns + 1) { Cost.ZERO }
        val p = IntArray(columns + 1)
        val way = IntArray(columns + 1)
        for (i in 1..rows) {
            cancellation.checkCanceled()
            p[0] = i
            var j0 = 0
            val minv = Array(columns + 1) { Cost.INFINITY }
            val used = BooleanArray(columns + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Cost.INFINITY
                var j1 = 0
                for (j in 1..columns) {
                    if (used[j]) continue
                    val current = costs[i0 - 1][j - 1] - u[i0] - v[j]
                    if (current < minv[j]) {
                        minv[j] = current
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                for (j in 0..columns) {
                    if (used[j]) {
                        u[p[j]] = u[p[j]] + delta
                        v[j] = v[j] - delta
                    } else {
                        minv[j] = minv[j] - delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val result = IntArray(rows) { -1 }
        for (j in 1..columns) {
            val row = p[j]
            if (row != 0) result[row - 1] = j - 1
        }
        return result
    }

    /**
     * A cost that compares by score first, then by [order]. The order is the pair-order bit vector: an assignment that
     * contains the smallest pair the other lacks is smaller, so the lexicographic pair list of R11 wins.
     */
    private class Cost(val score: Double, val order: BigInteger) : Comparable<Cost> {
        operator fun plus(other: Cost): Cost = Cost(score + other.score, order + other.order)

        operator fun minus(other: Cost): Cost = Cost(score - other.score, order - other.order)

        override fun compareTo(other: Cost): Int {
            val byScore = score.compareTo(other.score)
            return if (byScore != 0) byScore else order.compareTo(other.order)
        }

        companion object {
            val ZERO = Cost(0.0, BigInteger.ZERO)

            /** A cost no assignment takes while a dummy column is free; it keeps disallowed edges out. */
            val BLOCKED = Cost(1e9, BigInteger.ZERO)

            val INFINITY = Cost(Double.POSITIVE_INFINITY, BigInteger.ZERO)
        }
    }
}
