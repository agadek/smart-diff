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

    private companion object {
        const val BODY = "{ int x = compute(a, b); return x * 2; }"
    }
}
