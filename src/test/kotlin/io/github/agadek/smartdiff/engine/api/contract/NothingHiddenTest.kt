package io.github.agadek.smartdiff.engine.api.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NothingHiddenTest {

    @Test
    fun `agrees with the full dynamic programming on random texts and coverage`() {
        val random = Random(42)
        repeat(20_000) { case ->
            val old = text(random)
            val new = if (random.nextInt(4) == 0) text(random) else mutate(old, random)
            val oldCovered = coverage(old.length, random)
            val newCovered = coverage(new.length, random)
            assertEquals(
                "case $case: \"$old\" ${oldCovered.toList()} vs \"$new\" ${newCovered.toList()}",
                reference(old, oldCovered, new, newCovered),
                NothingHidden.alignable(old, oldCovered, new, newCovered),
            )
        }
    }

    @Test
    fun `checks large texts with local changes quickly`() {
        val lines = (1..20_000).map { "    val line$it = compute($it) // ${it % 7}\n" }
        val old = lines.joinToString("")
        val changed = lines.toMutableList().apply { this[100] = "    val inserted = 1\n"; this[15_000] = "    removed()\n" }
        val new = changed.joinToString("")
        val oldCovered = BooleanArray(old.length)
        val newCovered = BooleanArray(new.length)
        // Cover line 101 and line 15 001 on both sides.
        fun cover(covered: BooleanArray, text: List<String>, line: Int) {
            val start = text.take(line).sumOf { it.length }
            for (i in start until start + text[line].length) covered[i] = true
        }
        cover(oldCovered, lines, 100)
        cover(oldCovered, lines, 15_000)
        cover(newCovered, changed, 100)
        cover(newCovered, changed, 15_000)
        val start = System.nanoTime()
        assertTrue(NothingHidden.alignable(old, oldCovered, new, newCovered))
        val uncoveredNew = BooleanArray(new.length).also { cover(it, changed, 100) }
        assertEquals(false, NothingHidden.alignable(old, oldCovered, new, uncoveredNew))
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $millis ms for two ${old.length}-character texts", millis < 5_000)
    }

    private fun text(random: Random): String = (0 until random.nextInt(9)).map { "ab "[random.nextInt(3)] }.joinToString("")

    private fun mutate(text: String, random: Random): String {
        val chars = text.toMutableList()
        repeat(random.nextInt(3)) {
            when (random.nextInt(3)) {
                0 -> chars.add(random.nextInt(chars.size + 1), "abX"[random.nextInt(3)])
                1 -> if (chars.isNotEmpty()) chars.removeAt(random.nextInt(chars.size))
                else -> if (chars.isNotEmpty()) chars[random.nextInt(chars.size)] = "abX"[random.nextInt(3)]
            }
        }
        return chars.joinToString("")
    }

    private fun coverage(length: Int, random: Random): BooleanArray {
        val density = random.nextInt(4)
        return BooleanArray(length) { random.nextInt(4) < density }
    }

    /** The full table: whether the suffixes from (i, j) can be aligned. */
    private fun reference(old: String, oldCovered: BooleanArray, new: String, newCovered: BooleanArray): Boolean {
        val n = old.length
        val m = new.length
        val table = Array(n + 1) { BooleanArray(m + 1) }
        for (i in n downTo 0) for (j in m downTo 0) {
            table[i][j] = when {
                i == n && j == m -> true
                else -> (i < n && oldCovered[i] && table[i + 1][j]) ||
                    (j < m && newCovered[j] && table[i][j + 1]) ||
                    (i < n && j < m && old[i] == new[j] && table[i + 1][j + 1])
            }
        }
        return table[0][0]
    }
}
