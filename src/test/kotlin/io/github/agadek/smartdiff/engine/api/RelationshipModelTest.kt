package io.github.agadek.smartdiff.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet

/** The relationship model itself: defaults, kinds, endpoints, evidence and the score kept in evidence (relationships/02). */
class RelationshipModelTest {

    @Test
    fun `R1 a comparison result has a list of relationships, empty by default`() {
        val result = ComparisonResult(EngineId("test"), "1", Status.ANALYZED)

        assertEquals(emptyList<Relationship>(), result.relationships)
        assertEquals(listOf(relationship()), result.copy(relationships = listOf(relationship())).relationships)
    }

    @Test
    fun `R2 a relationship links one declaration to one or several on the other side`() {
        val one = relationship()
        assertEquals(1, one.old.size)
        assertEquals(1, one.new.size)

        val oneToMany = relationship(new = listOf(target("a"), target("b"), target("c")))
        assertEquals(3, oneToMany.new.size)

        assertThrows(IllegalArgumentException::class.java) { relationship(old = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { relationship(new = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { relationship(kinds = EnumSet.noneOf(RelationshipKind::class.java)) }
    }

    @Test
    fun `R3 kinds are one or more, in the order of the enum`() {
        assertEquals(listOf(RelationshipKind.RENAME, RelationshipKind.MOVE, RelationshipKind.KIND_CHANGE, RelationshipKind.EXTRACT, RelationshipKind.INLINE), RelationshipKind.entries)

        val all = EnumSet.of(RelationshipKind.MOVE, RelationshipKind.RENAME, RelationshipKind.EXTRACT, RelationshipKind.KIND_CHANGE, RelationshipKind.INLINE)
        assertEquals(
            listOf(RelationshipKind.RENAME, RelationshipKind.MOVE, RelationshipKind.KIND_CHANGE, RelationshipKind.EXTRACT, RelationshipKind.INLINE),
            relationship(kinds = all).kinds.toList(),
        )
        // A set built in another order does not iterate in enum order, so the model refuses it (R19).
        assertThrows(IllegalArgumentException::class.java) {
            relationship(kinds = linkedSetOf(RelationshipKind.MOVE, RelationshipKind.RENAME))
        }
    }

    @Test
    fun `R4 confidence is high, medium or low`() {
        assertEquals(listOf(Confidence.HIGH, Confidence.MEDIUM, Confidence.LOW), Confidence.entries)
        Confidence.entries.forEach { assertEquals(it, relationship(confidence = it).confidence) }
    }

    @Test
    fun `R5 evidence is name, content, structure, position or score with the ranges it compares`() {
        val content = Evidence.Content(shared = 12, total = 20, oldRange = TextRange(3, 9), newRange = TextRange(10, 18))
        val evidence = listOf(
            Evidence.Name("total", "sum"),
            content,
            Evidence.Structure("class Order", "class Order", DeclarationKind.METHOD, DeclarationKind.METHOD),
            Evidence.Position(2, 1),
            Evidence.Score(0.91),
        )

        assertEquals(evidence, relationship(evidence = evidence).evidence)

        assertEquals(TextRange(3, 9), content.oldRange)
        assertEquals(TextRange(10, 18), content.newRange)
        // The evidence order is fixed: out of order is refused (R19).
        assertThrows(IllegalArgumentException::class.java) { relationship(evidence = evidence.reversed()) }
    }

    @Test
    fun `R6 the numeric score stays in the evidence and the API exposes a word`() {
        val score = Evidence.Score(0.91)
        assertEquals(0.91, score.value, 0.0)
        assertEquals(Confidence.HIGH, ConfidencePolicy(high = 0.9, medium = 0.7).classify(score.value))
        // The score is a double in the evidence, not a percentage in the confidence.
        assertTrue(Confidence.values().none { it.name.contains('%') })
    }

    @Test
    fun `a confidence policy classifies at its thresholds and parses its registry form`() {
        val policy = ConfidencePolicy(high = 0.85, medium = 0.7)

        assertEquals(Confidence.HIGH, policy.classify(0.85))
        assertEquals(Confidence.MEDIUM, policy.classify(0.7))
        assertEquals(Confidence.MEDIUM, policy.classify(0.84))
        assertEquals(Confidence.LOW, policy.classify(0.699))
        assertEquals("0.85,0.7", policy.format())

        assertEquals(policy, ConfidencePolicy.parse("0.85,0.7"))
        assertEquals(policy, ConfidencePolicy.parse(" 0.85 , 0.7 "))
        assertNull(ConfidencePolicy.parse(null))
        assertNull(ConfidencePolicy.parse(""))
        assertNull(ConfidencePolicy.parse("   "))
        assertNull(ConfidencePolicy.parse("0.85"))
        assertNull(ConfidencePolicy.parse("high,medium"))
        assertNull("medium above high", ConfidencePolicy.parse("0.6,0.8"))
        assertNull("above one", ConfidencePolicy.parse("1.1,0.7"))
        assertNull("negative", ConfidencePolicy.parse("-0.1,0.7"))
        assertNull(ConfidencePolicy.parse("0.85,0.7,0.5"))
        assertThrows(IllegalArgumentException::class.java) { ConfidencePolicy(high = 0.6, medium = 0.7) }
    }

    private fun relationship(
        kinds: Set<RelationshipKind> = EnumSet.of(RelationshipKind.RENAME),
        old: List<RelationshipEndpoint> = listOf(subject("total")),
        new: List<RelationshipEndpoint> = listOf(target("sum")),
        confidence: Confidence = Confidence.MEDIUM,
        evidence: List<Evidence> = listOf(Evidence.Name("total", "sum"), Evidence.Score(0.8)),
    ) = Relationship(kinds, old, new, confidence, evidence)

    private fun subject(name: String) = RelationshipEndpoint(Subject.Declaration(DeclarationKind.METHOD, name, ""), TextRange(0, 5))

    private fun target(name: String) = RelationshipEndpoint(Subject.Declaration(DeclarationKind.METHOD, name, ""), TextRange(6, 11))
}
