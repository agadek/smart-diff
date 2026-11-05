package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.ConfidencePolicy
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.random.Random

/** Relationships/03: the rename score, the whole-file assignment, ties and the limits, on hand-built trees. */
class RelationshipMatchingTest {

    @Test
    fun `R1 M1 M2 M4 M4b and the unique-key part of M3 still pair as before`() {
        // relationships/03-relationship-matching R1

        val identity = match(
            tree { cls("A") { method("foo", "int") } },
            tree { cls("A") { method("foo", "int") } },
        )
        assertSame(identity.newTree.named("foo"), identity.matching.ofOld(identity.oldTree.named("foo"))!!.new)
        assertTrue(identity.matching.relationships.isEmpty())

        val moved = match(
            tree { cls("A") { method("foo", "int") }; cls("B") },
            tree { cls("A"); cls("B") { method("foo", "int") } },
        )
        assertTrue(moved.matching.ofOld(moved.oldTree.named("foo"))!!.moved)

        val uncertain = match(
            tree { cls("A") { method("foo", "int"); method("foo", "String") } },
            tree { cls("A") { method("foo", "long"); method("foo", "char"); method("foo", "byte") } },
        )
        assertTrue(uncertain.matching.uncertainOld.isNotEmpty())
        assertTrue("uncertainty is not a relationship", uncertain.matching.relationships.isEmpty())

        val kindChanged = match(
            tree { declaration(DeclarationKind.CLASS, "Shape") },
            tree { declaration(DeclarationKind.INTERFACE, "Shape") },
        )
        assertSame(kindChanged.newTree.named("Shape"), kindChanged.matching.ofOld(kindChanged.oldTree.named("Shape"))!!.new)

        val signatureChanged = match(
            tree { cls("A") { method("foo", "int") } },
            tree { cls("A") { method("foo", "long") } },
        )
        assertSame(signatureChanged.newTree.named("foo"), signatureChanged.matching.ofOld(signatureChanged.oldTree.named("foo"))!!.new)
    }

    @Test
    fun `R2 changing one rename feature changes the score`() {
        // relationships/03-relationship-matching R2
        val base = renameScore(
            tree { cls("A") { method("total", "int", BODY) } },
            tree { cls("A") { method("sum", "int", BODY) } },
        )
        val name = renameScore(
            tree { cls("A") { method("total", "int", BODY) } },
            tree { cls("A") { method("total2", "int", BODY) } },
        )
        val parameters = renameScore(
            tree { cls("A") { method("total", "int", BODY) } },
            tree { cls("A") { method("sum", "long", BODY) } },
        )
        val order = renameScore(
            tree { cls("A") { method("other", "", "{ a(); b(); c(); }"); method("total", "int", BODY) } },
            tree { cls("A") { method("sum", "int", BODY); method("other", "", "{ a(); b(); c(); }") } },
        )
        val context = renameScore(
            tree { cls("A") { method("anchor", "", "{ a(); b(); c(); }"); method("total", "int", BODY) } },
            tree { cls("A") { method("anchor", "", "{ a(); b(); c(); }"); method("sum", "int", BODY) } },
        )

        assertTrue("the base pair scores above the floor: $base", base >= 0.7)
        assertTrue("a closer name adds to the score: $name <= $base", name > base)
        assertTrue("a changed parameter type lowers the score: $parameters >= $base", parameters < base)
        assertTrue("a worse order lowers the score: $order >= $base", order < base)
        assertTrue("a matched neighbour adds to the score: $context <= $base", context > base)
    }

    @Test
    fun `R3 order alone and context plus order never pair`() {
        // relationships/03-relationship-matching R3
        val orderOnly = match(
            tree { cls("A") { method("alpha", "", "{ one(); two(); three(); }") } },
            tree { cls("A") { method("omega", "long", "{ four(); five(); six(); }") } },
        )
        assertTrue("order only leaves both sides alone", orderOnly.matching.matches.size == 1)
        assertTrue(orderOnly.matching.removed.any { it.name == "alpha" })
        assertTrue(orderOnly.matching.added.any { it.name == "omega" })

        val contextAndOrder = match(
            tree { cls("A") { method("anchor", "", "{ a(); b(); c(); }"); method("alpha", "", "{ one(); two(); three(); }") } },
            tree { cls("A") { method("anchor", "", "{ a(); b(); c(); }"); method("omega", "long", "{ four(); five(); six(); }") } },
        )
        assertTrue(contextAndOrder.matching.removed.any { it.name == "alpha" })
        assertTrue(contextAndOrder.matching.added.any { it.name == "omega" })
    }

    @Test
    fun `B1 the search finds the largest feasible assignment in a 3 by 4 counterexample`() {
        // relationships/03-relationship-matching B1: solve-review-drop returns nothing, the exact search returns 2.50.
        val scores = arrayOf(
            doubleArrayOf(0.95, 0.80, 0.90, 0.65),
            doubleArrayOf(0.75, 0.80, 0.80, 0.95),
            doubleArrayOf(0.00, 0.50, 0.60, 0.90),
        )
        val result = GlobalAssignment.solve(scores, 0.5, null, Cancellation.NONE)
        assertEquals(listOf(0 to 0, 1 to 3, 2 to 2), result)
        assertEquals("the exhaustive maximum is the same total", 2.50, bruteForceMaximum(scores, 0.5), 1e-9)
        assertEquals(2.50, result.sumOf { (i, j) -> scores[i][j] }, 1e-9)
    }

    @Test
    fun `B1 the search takes the 2 point 52 assignment with only n0 unmatched`() {
        // relationships/03-relationship-matching B1: (0,3),(1,2),(2,1) is feasible with only new column 0 unmatched.
        val scores = arrayOf(
            doubleArrayOf(0.72, 0.10, 0.10, 0.90),
            doubleArrayOf(0.60, 0.10, 0.72, 0.10),
            doubleArrayOf(0.60, 0.90, 0.10, 0.10),
        )
        val result = GlobalAssignment.solve(scores, 0.5, null, Cancellation.NONE)
        assertEquals(listOf(0 to 3, 1 to 2, 2 to 1), result)
        assertEquals("the exhaustive maximum is 2.52", 2.52, bruteForceMaximum(scores, 0.5), 1e-9)
        assertEquals(2.52, result.sumOf { (i, j) -> scores[i][j] }, 1e-9)
    }

    @Test
    fun `B2 a reordered rename with body change content 075 still pairs`() {
        // relationships/03-relationship-matching B2: `total` is reordered and its body changes to content 0.75.
        val old = tree { cls("A") { method("other", "", "{ a(); b(); c(); }"); method("total", "int", BODY) } }
        val new = tree { cls("A") { method("sum", "int", BODY.replace("x", "y")); method("other", "", "{ a(); b(); c(); }") } }

        val relationship = match(old, new).matching.relationships.single { RelationshipKind.RENAME in it.kinds }

        assertEquals(setOf(RelationshipKind.RENAME, RelationshipKind.MOVE), relationship.kinds)
        val score = relationship.evidence.filterIsInstance<Evidence.Score>().single().value
        assertTrue("the original fixture scores just above the floor: $score (expected 0.7025)", score >= 0.7)
        assertTrue("the margin above the floor is small", score < 0.71)
    }

    @Test
    fun `B1 a group at the search cap keeps a feasible deterministic result`() {
        // relationships/03-relationship-matching B1 and R4: the cap returns the best feasible assignment found.
        val scores = arrayOf(
            doubleArrayOf(0.95, 0.80, 0.90, 0.65),
            doubleArrayOf(0.75, 0.80, 0.80, 0.95),
            doubleArrayOf(0.00, 0.50, 0.60, 0.90),
        )
        val first = GlobalAssignment.solve(scores, 0.5, null, Cancellation.NONE, maxNodes = 1)
        val second = GlobalAssignment.solve(scores, 0.5, null, Cancellation.NONE, maxNodes = 1)
        assertEquals("the capped result is deterministic", first, second)
        assertTrue("the capped result is feasible: $first", feasibleAssignment(scores, first))
    }

    @Test
    fun `R4 a global assignment pairs both where greedy leaves one out`() {
        // relationships/03-relationship-matching R4
        val scores = arrayOf(
            doubleArrayOf(0.90, 0.85),
            doubleArrayOf(0.85, 0.00),
        )
        assertEquals(
            setOf(0 to 1, 1 to 0),
            GlobalAssignment.solve(scores, 0.7, null, Cancellation.NONE).toSet(),
        )
    }

    @Test
    fun `R5 a near tie with unclear names stays unpaired and a clear name decides`() {
        // relationships/03-relationship-matching R5
        val nearTie = arrayOf(doubleArrayOf(0.90, 0.90))
        assertTrue("a close rival leaves the pair unpaired", GlobalAssignment.solve(nearTie, 0.7, arrayOf(doubleArrayOf(0.0, 0.0)), Cancellation.NONE).isEmpty())

        val names = arrayOf(doubleArrayOf(1.0, 0.0))
        assertEquals(listOf(0 to 0), GlobalAssignment.solve(nearTie, 0.7, names, Cancellation.NONE))

        val belowFloorRival = arrayOf(doubleArrayOf(0.75, 0.66))
        assertTrue(
            "a below-floor rival within the margin still refuses the pair",
            GlobalAssignment.solve(belowFloorRival, 0.7, null, Cancellation.NONE).isEmpty(),
        )
    }

    @Test
    fun `R6 a tie is a low relationship that pairs nothing`() {
        // relationships/03-relationship-matching R6
        val shared = "{ int a = 1; int b = 2; int c = 3; int d = 4; int e = 5; return a + b + c + d + e; }"
        val old = tree {
            cls("A") {
                method("total", "int", shared)
                method("count", "int", shared.replace("e = 5", "e = 6"))
            }
        }
        val new = tree { cls("A") { method("sum", "int", shared) } }

        val matching = match(old, new).matching
        val ties = matching.relationships.filter { RelationshipKind.RENAME in it.kinds && it.confidence == Confidence.LOW }
        assertEquals("one low relationship for each tied option", 2, ties.size)
        val score = ties.first().evidence.filterIsInstance<Evidence.Score>().single().value
        assertTrue("the tie scores at or above high and is still low: $score", score >= ConfidencePolicy.DEFAULT.high)
        ties.forEach {
            assertEquals(setOf(RelationshipKind.RENAME), it.kinds)
            assertTrue(it.evidence.any { evidence -> evidence is Evidence.Name })
            assertTrue(it.evidence.any { evidence -> evidence is Evidence.Score })
            assertFalse("a tie claims no position", it.evidence.any { evidence -> evidence is Evidence.Position })
            assertFalse("a tie claims no structure", it.evidence.any { evidence -> evidence is Evidence.Structure })
        }
        assertTrue(matching.removed.map { it.name }.containsAll(listOf("total", "count")))
        assertTrue(matching.added.any { it.name == "sum" })

        val result = DeclarationAnalysis(EngineId("smartdiff.test.declarations"), "1")
            .compare(old, new, 5_000, Cancellation.NONE)
        val sum = result.changes.flatMap { listOf(it) + it.children }.single { it.subject.name() == "sum" }
        assertNull("a low tie sets no rename", sum.renamedFrom)
        assertEquals(Presence.ADDED, sum.presence)
    }

    @Test
    fun `R7 duplicate bodies pair only when another feature is clear`() {
        // relationships/03-relationship-matching R7
        val unclear = match(
            tree { cls("A") { method("alpha", "", BODY); method("beta", "", BODY) } },
            tree { cls("A") { method("gamma", "", BODY); method("delta", "", BODY) } },
        )
        assertTrue("content alone does not pair duplicate bodies", unclear.matching.matches.size == 1)
        assertTrue(unclear.matching.removed.map { it.name }.containsAll(listOf("alpha", "beta")))
        assertTrue(unclear.matching.added.map { it.name }.containsAll(listOf("gamma", "delta")))

        val clearName = match(
            tree { cls("A") { method("computeTotal", "", BODY); method("computeCount", "", BODY) } },
            tree { cls("A") { method("computeTotal2", "", BODY); method("computeCount2", "", BODY) } },
        )
        assertSame(clearName.newTree.named("computeTotal2"), clearName.matching.ofOld(clearName.oldTree.named("computeTotal"))!!.new)
        assertSame(clearName.newTree.named("computeCount2"), clearName.matching.ofOld(clearName.oldTree.named("computeCount"))!!.new)
    }

    @Test
    fun `R8 an overload tie stays uncertain with candidates and no relationship`() {
        // relationships/03-relationship-matching R8
        val old = tree { cls("A") { method("of", "int", "{ one(); two(); three(); }"); method("of", "long", "{ four(); five(); six(); }") } }
        val new = tree { cls("A") { method("of", "short", "{ seven(); eight(); nine(); }"); method("of", "byte", "{ ten(); eleven(); twelve(); }"); method("of", "char", "{ a(); b(); c(); }") } }

        val matching = match(old, new).matching

        assertEquals(2, matching.uncertainOld.size)
        assertEquals(3, matching.uncertainNew.size)
        assertTrue(matching.uncertainOld.all { it.candidates.isNotEmpty() })
        assertTrue("an overload tie is not a relationship", matching.relationships.isEmpty())
    }

    @Test
    fun `R9 a kind change with an order change carries both kinds`() {
        // relationships/03-relationship-matching R9
        val old = tree {
            cls("A") {
                declaration(DeclarationKind.FUNCTION, "size", body = "def size = n")
                declaration(DeclarationKind.FUNCTION, "name", body = "def name = s")
            }
        }
        val new = tree {
            cls("A") {
                declaration(DeclarationKind.PROPERTY, "name", body = "val name = s")
                declaration(DeclarationKind.PROPERTY, "size", body = "val size = n")
            }
        }

        val matching = match(old, new).matching
        val name = matching.matches.single { it.old.name == "name" }
        assertEquals(MatchRule.KIND_CHANGED, name.rule)
        assertTrue("the later old declaration moved", name.orderChanged)
        val relationship = matching.relationships.single { it.old.first().range == name.old.range }
        assertEquals(setOf(RelationshipKind.KIND_CHANGE, RelationshipKind.MOVE), relationship.kinds)
    }

    @Test
    fun `R10 R19 a policy changes the confidence but never the pairs`() {
        // relationships/03-relationship-matching R10, R19
        val old = tree { cls("A") { method("total", "int", BODY) } }
        val new = tree { cls("A") { method("sum", "int", BODY) } }
        val score = renameScore(old, new)

        val high = ConfidencePolicy(high = score, medium = score)
        val medium = ConfidencePolicy(high = score + (1.0 - score) / 2, medium = score)
        val low = ConfidencePolicy(high = 1.0, medium = 1.0)

        val pairs = listOf(high, medium, low).map { policy ->
            match(old, new, policy).matching.matches.map { it.toString() }
        }
        assertEquals("the same pairs under every policy", pairs[0], pairs[1])
        assertEquals(pairs[0], pairs[2])
        assertEquals(Confidence.HIGH, renameRelationship(old, new, high).confidence)
        assertEquals(Confidence.MEDIUM, renameRelationship(old, new, medium).confidence)
        assertEquals(Confidence.LOW, renameRelationship(old, new, low).confidence)
    }

    @Test
    fun `R11 equal totals go to the old and then the new order`() {
        // relationships/03-relationship-matching R11
        val tie = arrayOf(
            doubleArrayOf(0.8, 0.8),
            doubleArrayOf(0.8, 0.8),
        )
        assertEquals(listOf(0 to 0, 1 to 1), GlobalAssignment.solve(tie, 0.7, null, Cancellation.NONE))

        val marginWins = arrayOf(
            doubleArrayOf(0.8, 0.75),
            doubleArrayOf(0.0, 0.8),
        )
        assertEquals(setOf(0 to 0, 1 to 1), GlobalAssignment.solve(marginWins, 0.7, null, Cancellation.NONE).toSet())
    }

    @Test
    fun `R12 a group over the pair limit is not compared`() {
        // relationships/03-relationship-matching R12
        val old = tree {
            cls("A") {
                for (i in 0..100) method("old$i", "", BODY)
            }
        }
        val new = tree {
            cls("A") {
                for (i in 0..99) method("new$i", "", BODY)
            }
        }

        val matching = match(old, new).matching
        assertEquals("the class still matches, but no method in the oversized group pairs", 101, matching.removed.size)
        assertEquals(100, matching.added.size)
        assertTrue(matching.relationships.isEmpty())

        val overloadOld = tree {
            cls("A") { for (i in 0..100) method("of", "T$i", BODY) }
        }
        val overloadNew = tree {
            cls("A") { for (i in 0..99) method("of", "U$i", BODY) }
        }
        val overload = match(overloadOld, overloadNew).matching
        assertEquals("the oversized M3 group goes to M4", 101, overload.uncertainOld.size)
        assertEquals(100, overload.uncertainNew.size)
        assertTrue(overload.relationships.isEmpty())
    }

    @Test
    fun `R12 a large near-tied group spends its work budget and returns promptly`() {
        // relationships/03-relationship-matching R12: before the work budget, 100 by 99 near-tied scores took ~12 s.
        val random = Random(99)
        val scores = Array(100) { DoubleArray(99) { 0.70 + random.nextDouble() * 0.25 } }

        val start = System.nanoTime()
        val pairs = GlobalAssignment.solve(scores, 0.7, null, Cancellation.NONE)
        val millis = (System.nanoTime() - start) / 1_000_000

        assertTrue("the group is left unpaired when its work runs out: $pairs", pairs.isEmpty())
        assertTrue("the group returns within the analysis limit, took ${millis}ms", millis < 4_000)
    }

    @Test
    fun `R12 the default work budget keeps a moderate group's pair`() {
        // relationships/03-relationship-matching R12: this 50 by 40 group needs about 3.6M work and returns in about
        // 0.13 s; the earlier 2M default left it unpaired.
        val random = Random(1)
        val scores = Array(50) { i ->
            DoubleArray(40) { j ->
                if (i == 0 && j == 0) 1.0 else 0.70 + random.nextDouble() * 0.15
            }
        }
        for (j in 1 until 40) scores[0][j] = 0.0
        for (i in 1 until 50) scores[i][0] = 0.0

        assertEquals(listOf(0 to 0), GlobalAssignment.solve(scores, 0.7, null, Cancellation.NONE))
    }

    @Test
    fun `R12 a group over the work limit is left unpaired and below it stays exact`() {
        // relationships/03-relationship-matching R12: the work limit changes only groups that exceed it.
        val scores = arrayOf(
            doubleArrayOf(0.90, 0.85),
            doubleArrayOf(0.85, 0.00),
        )
        assertEquals(
            setOf(0 to 1, 1 to 0),
            GlobalAssignment.solve(scores, 0.7, null, Cancellation.NONE, maxWork = Long.MAX_VALUE).toSet(),
        )
        assertTrue(
            "a group over its work limit is left unpaired",
            GlobalAssignment.solve(scores, 0.7, null, Cancellation.NONE, maxWork = 1).isEmpty(),
        )
    }

    @Test(expected = CancellationException::class)
    fun `R13 cancellation stops the assignment`() {
        // relationships/03-relationship-matching R13
        val scores = arrayOf(
            doubleArrayOf(0.8, 0.8, 0.8),
            doubleArrayOf(0.8, 0.8, 0.8),
            doubleArrayOf(0.8, 0.8, 0.8),
        )
        var checks = 0
        val cancellation = Cancellation {
            checks++
            if (checks >= 2) throw CancellationException()
        }
        GlobalAssignment.solve(scores, 0.7, null, cancellation)
    }

    private class Matched(val oldTree: DeclarationTree, val newTree: DeclarationTree, val matching: Matching)

    private fun match(old: DeclarationTree, new: DeclarationTree, confidence: ConfidencePolicy = ConfidencePolicy.DEFAULT): Matched =
        Matched(old, new, DeclarationMatcher.match(old, new, confidence, Cancellation.NONE))

    private fun renameRelationship(old: DeclarationTree, new: DeclarationTree, confidence: ConfidencePolicy): io.github.agadek.smartdiff.engine.api.Relationship =
        match(old, new, confidence).matching.relationships.single { RelationshipKind.RENAME in it.kinds }

    private fun renameScore(old: DeclarationTree, new: DeclarationTree): Double =
        renameRelationship(old, new, ConfidencePolicy.DEFAULT).evidence.filterIsInstance<Evidence.Score>().single().value

    private fun io.github.agadek.smartdiff.engine.api.Subject.name(): String = when (this) {
        is Subject.Declaration -> name
        is Subject.Region -> description
    }

    /**
     * An independent exhaustive reference for the 3 by 4 B1 fixtures: every row may be unpaired or take one free allowed
     * column, and an assignment counts only when every chosen edge passes the margin rule.
     */
    private fun feasibleAssignment(scores: Array<DoubleArray>, pairs: List<Pair<Int, Int>>): Boolean {
        val olds = scores.size
        val news = scores[0].size
        val pairedOld = pairs.map { it.first }.toSet()
        val pairedNew = pairs.map { it.second }.toSet()
        if (pairedOld.size != pairs.size || pairedNew.size != pairs.size) return false
        for ((i, j) in pairs) {
            if (scores[i][j] < 0.5) return false
            val close = scores[i][j] - ASSIGNMENT_MARGIN
            if ((0 until olds).any { it != i && it !in pairedOld && scores[it][j] > close }) return false
            if ((0 until news).any { it != j && it !in pairedNew && scores[i][it] > close }) return false
        }
        return true
    }

    private fun bruteForceMaximum(scores: Array<DoubleArray>, floor: Double): Double {
        val olds = scores.size
        val news = scores[0].size
        val partner = IntArray(olds) { -1 }
        val used = BooleanArray(news)
        var best = 0.0

        fun feasible(): Boolean {
            val pairedNew = BooleanArray(news)
            for (column in partner) if (column >= 0) pairedNew[column] = true
            val unpairedOld = BooleanArray(olds) { partner[it] < 0 }
            for (i in 0 until olds) {
                val j = partner[i]
                if (j < 0) continue
                val close = scores[i][j] - ASSIGNMENT_MARGIN
                if ((0 until olds).any { it != i && unpairedOld[it] && scores[it][j] > close }) return false
                if ((0 until news).any { it != j && !pairedNew[it] && scores[i][it] > close }) return false
            }
            return true
        }

        fun search(row: Int, total: Double) {
            if (row == olds) {
                if (feasible()) best = maxOf(best, total)
                return
            }
            partner[row] = -1
            search(row + 1, total)
            for (column in 0 until news) {
                if (used[column] || scores[row][column] < floor) continue
                used[column] = true
                partner[row] = column
                search(row + 1, total + scores[row][column])
                used[column] = false
                partner[row] = -1
            }
        }

        search(0, 0.0)
        return best
    }

    private companion object {
        const val BODY = "{ int x = compute(a, b); return x * 2; }"
    }
}
