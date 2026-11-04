package io.github.agadek.smartdiff.engine.api.contract

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Origin
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipEndpoint
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.lines.LineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet
import java.util.concurrent.CancellationException

/** The contract suite itself: its corpus is meaningful and each check can fail. */
class ContractSuiteSelfTest {

    @Test
    fun `every corpus case changes something`() {
        (ContractCorpus.cases + ContractCorpus.large(ContractCorpus.JAVA) + ContractCorpus.large(ContractCorpus.KOTLIN)).forEach {
            assertNotEquals("$it has equal sides", it.oldText, it.newText)
        }
    }

    @Test
    fun `every corpus language has cases`() {
        ContractCorpus.languages.forEach { language ->
            assertTrue("no well-formed case for $language", ContractCorpus.cases.any { it.languageId == language && !it.malformed })
            assertTrue("no empty case for $language", ContractCorpus.emptyCases.any { it.languageId == language })
        }
    }

    @Test
    fun `nothing hidden holds when every change is covered`() {
        val result = result(ChangeNode(Subject.Region("b"), TextRange(1, 2), TextRange(1, 3), Presence.MATCHED, content = Content.CHANGED))
        assertNull(NothingHidden.violation("abc", "aXYc", result))
    }

    @Test
    fun `nothing hidden does not depend on one alignment`() {
        // Removing either "a" is a valid character diff; the engine chose the second one.
        val result = result(ChangeNode(Subject.Region("a"), TextRange(1, 2), null, Presence.REMOVED))
        assertNull(NothingHidden.violation("aa", "a", result))
    }

    @Test
    fun `nothing hidden fails on an uncovered difference`() {
        val result = result(ChangeNode(Subject.Region("a"), TextRange(0, 1), TextRange(0, 1), Presence.MATCHED, content = Content.CHANGED))
        assertNotNull(NothingHidden.violation("abc", "Xbd", result))
    }

    @Test
    fun `a container shown only for its children covers nothing itself`() {
        val child = ChangeNode(Subject.Region("c"), TextRange(2, 3), TextRange(2, 3), Presence.MATCHED, content = Content.CHANGED)
        val container = ChangeNode(Subject.Region("all"), TextRange(0, 4), TextRange(0, 4), Presence.MATCHED, children = listOf(child))
        assertNull(NothingHidden.violation("abcd", "abXd", result(container)))
        assertNotNull(NothingHidden.violation("abcd", "YbXd", result(container)))
    }

    @Test
    fun `unanalyzed ranges and details count as covered`() {
        val withDetails = ChangeNode(Subject.Region("x"), TextRange(0, 3), TextRange(0, 3), Presence.MATCHED, details = listOf(RangePair(TextRange(1, 2), TextRange(1, 2))))
        assertNull(NothingHidden.violation("abc", "aXc", result(withDetails)))
        val partial = ComparisonResult(EngineId("t"), "1", Status.PARTIAL, unanalyzed = listOf(RangePair(TextRange(0, 3), null)))
        assertNull(NothingHidden.violation("abc", "", partial))
    }

    @Test
    fun `the suite rejects an engine that hides changes`() {
        val contract = ComparisonEngineContract { HidingEngine() }
        assertThrows(AssertionError::class.java) { contract.`results are well-formed and hide nothing`() }
    }

    @Test
    fun `the suite rejects an engine that ignores cancellation`() {
        val contract = ComparisonEngineContract { IgnoringCancellation() }
        assertThrows(AssertionError::class.java) { contract.`cancellation propagates promptly`() }
    }

    @Test
    fun `the suite rejects an engine with state between calls`() {
        val contract = ComparisonEngineContract { Stateful() }
        assertThrows(AssertionError::class.java) { contract.`results are deterministic`() }
    }

    @Test
    fun `the suite rejects an engine that throws on malformed input`() {
        val contract = ComparisonEngineContract { Throwing() }
        val error = assertThrows(AssertionError::class.java) { contract.`malformed input gives a result, not an exception`() }
        assertEquals(IllegalStateException::class.java, error.cause?.javaClass)
    }

    @Test
    fun `the suite rejects an origin that names no reported matched node`() {
        val contract = ComparisonEngineContract { DanglingOrigin() }
        assertThrows(AssertionError::class.java) { contract.`results are well-formed and hide nothing`() }
    }

    @Test
    fun `the relationship checks pass on a well-formed result`() {
        val result = covered("abc", "abd")

        RelationshipChecks.endpointsInResultTree(CASE, result)
        RelationshipChecks.rangesInsideText(CASE, result)
        RelationshipChecks.extractionFieldsAgree(CASE, result)
        RelationshipChecks.fixedOrder(CASE, result)
        RelationshipChecks.nothingHiddenWithoutRelationships(CASE, result)
    }

    @Test
    fun `the relationship checks reject an endpoint that names no node R16`() {
        // relationships/02-relationship-model R16, R23
        val bad = result(
            ChangeNode(Subject.Region("all"), TextRange(0, 3), TextRange(0, 3), Presence.MATCHED, content = Content.CHANGED),
        ).copy(relationships = listOf(rename(TextRange(0, 1), "a", TextRange(0, 1), "a")))

        assertThrows(AssertionError::class.java) { RelationshipChecks.endpointsInResultTree(CASE, bad) }
    }

    @Test
    fun `the relationship checks accept a moved declaration whose signature also changed R16`() {
        // relationships/02-relationship-model R16, R23
        val node = ChangeNode(
            method("run", "(int)"), TextRange(0, 3), TextRange(0, 3), Presence.MATCHED,
            moved = true, signatureChanged = true, content = Content.CHANGED,
        )
        val moved = Relationship(
            kinds = EnumSet.of(RelationshipKind.MOVE),
            old = listOf(endpoint(method("run", "(long)"), TextRange(0, 3))),
            new = listOf(endpoint(method("run", "(int)"), TextRange(0, 3))),
            confidence = Confidence.HIGH,
            evidence = emptyList(),
        )

        // The endpoint carries the old signature while the node's subject carries the new one; the range identifies it.
        RelationshipChecks.endpointsInResultTree(CASE, result(node).copy(relationships = listOf(moved)))

        // Another declaration at that range is still rejected.
        val other = moved.copy(old = listOf(endpoint(method("other", "(long)"), TextRange(0, 3))))
        assertThrows(AssertionError::class.java) {
            RelationshipChecks.endpointsInResultTree(CASE, result(node).copy(relationships = listOf(other)))
        }
    }

    @Test
    fun `the relationship checks accept an extraction whose source was renamed R18`() {
        // relationships/02-relationship-model R18, R23
        val case = ContractCase("extract", ContractCorpus.TEXT, "aaa", "bbb")
        val describe = method("helper", "()")
        val source = ChangeNode(
            method("sum", "(int)"), TextRange(0, 2), TextRange(0, 1), Presence.MATCHED, content = Content.CHANGED,
        ).copy(
            renamedFrom = method("total", "(int)"),
            extractedTo = listOf(describe),
            details = listOf(RangePair(TextRange(0, 1), null)),
        )
        val target = ChangeNode(describe, null, TextRange(1, 3), Presence.ADDED).copy(
            extractedFrom = listOf(Origin(method("sum", "(int)"), TextRange(0, 2), TextRange(0, 1))),
        )
        val link = Relationship(
            kinds = EnumSet.of(RelationshipKind.EXTRACT),
            old = listOf(endpoint(method("total", "(int)"), TextRange(0, 2))),
            new = listOf(endpoint(describe, TextRange(1, 3))),
            confidence = Confidence.HIGH,
            evidence = listOf(Evidence.Content(1, 2, TextRange(0, 1), TextRange(1, 3)), Evidence.Score(0.5)),
        )

        // The Origin names the source as the matched node shows it (sum), while the endpoint names the old one (total).
        RelationshipChecks.extractionFieldsAgree(case, result(source, target).copy(relationships = listOf(link)))
    }

    @Test
    fun `the relationship checks reject a range outside the text R17`() {
        // relationships/02-relationship-model R17, R23
        val bad = result(
            ChangeNode(Subject.Region("all"), TextRange(0, 3), TextRange(0, 3), Presence.MATCHED, content = Content.CHANGED),
        ).copy(relationships = listOf(rename(TextRange(0, 9), "a", TextRange(0, 9), "b")))

        assertThrows(AssertionError::class.java) { RelationshipChecks.rangesInsideText(CASE, bad) }
    }

    @Test
    fun `the relationship checks reject a relationship listed twice R18`() {
        // relationships/02-relationship-model R18, R23
        val duplicate = rename(TextRange(0, 3), "a", TextRange(0, 3), "b")
        val bad = result(
            ChangeNode(Subject.Region("all"), TextRange(0, 3), TextRange(0, 3), Presence.MATCHED, content = Content.CHANGED),
        ).copy(relationships = listOf(duplicate, duplicate))

        assertThrows(AssertionError::class.java) { RelationshipChecks.extractionFieldsAgree(CASE, bad) }
    }

    @Test
    fun `the relationship checks reject a list out of order R19`() {
        // relationships/02-relationship-model R19, R23
        val node = ChangeNode(Subject.Region("all"), TextRange(0, 3), TextRange(0, 3), Presence.MATCHED, content = Content.CHANGED)
        val first = rename(TextRange(0, 1), "a", TextRange(0, 1), "a")
        val second = rename(TextRange(2, 3), "c", TextRange(2, 3), "d")
        val bad = result(node).copy(relationships = listOf(second, first))

        assertThrows(AssertionError::class.java) { RelationshipChecks.fixedOrder(CASE, bad) }
    }

    @Test
    fun `the relationship checks reject a result that hides a difference R20`() {
        // relationships/02-relationship-model R20, R23
        val covering = ChangeNode(Subject.Region("a"), TextRange(0, 1), TextRange(0, 1), Presence.MATCHED, content = Content.CHANGED)
        val bad = result(covering).copy(relationships = listOf(rename(TextRange(0, 1), "a", TextRange(0, 1), "a")))

        assertThrows(AssertionError::class.java) { RelationshipChecks.nothingHiddenWithoutRelationships(CASE, bad) }
    }

    /** A result that covers every difference between the two texts and links the one node. */
    private fun covered(old: String, new: String): ComparisonResult = ComparisonResult(
        EngineId("t"), "1", Status.ANALYZED,
        changes = listOf(ChangeNode(Subject.Region("all"), TextRange(0, old.length), TextRange(0, new.length), Presence.MATCHED, content = Content.CHANGED)),
        relationships = listOf(
            rename(TextRange(0, old.length), "all", TextRange(0, new.length), "all"),
        ),
    )

    private fun method(name: String, signature: String?) = Subject.Declaration(DeclarationKind.METHOD, name, signature)

    private fun endpoint(subject: Subject, range: TextRange) = RelationshipEndpoint(subject, range)

    private fun rename(old: TextRange, oldName: String, new: TextRange, newName: String) = Relationship(
        kinds = java.util.EnumSet.of(RelationshipKind.RENAME),
        old = listOf(RelationshipEndpoint(Subject.Region(oldName), old)),
        new = listOf(RelationshipEndpoint(Subject.Region(newName), new)),
        confidence = Confidence.HIGH,
        evidence = emptyList(),
    )

    private fun result(vararg changes: ChangeNode) = ComparisonResult(EngineId("t"), "1", Status.ANALYZED, changes = changes.toList())

    private companion object {
        /** Two texts that differ at the last character, and one result node that covers all of both. */
        val CASE = ContractCase("tiny", ContractCorpus.TEXT, "abc", "abd")
    }

    private open class Delegating(private val delegate: ComparisonEngine = LineEngine()) : ComparisonEngine by delegate

    /** Reports nothing, whatever changed. */
    private class HidingEngine : Delegating() {
        override fun compare(request: ComparisonRequest, cancellation: Cancellation) = ComparisonResult(id, version, Status.ANALYZED)
    }

    private class IgnoringCancellation : Delegating() {
        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult = try {
            super.compare(request, cancellation)
        } catch (e: CancellationException) {
            ComparisonResult(id, version, Status.ANALYZED)
        }
    }

    private class Stateful : Delegating() {
        private var calls = 0
        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult =
            super.compare(request, cancellation).copy(fallbackReason = "call ${calls++}")
    }

    /** Adds an extraction link whose source declaration is not in the result. */
    private class DanglingOrigin : Delegating() {
        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
            val result = super.compare(request, cancellation)
            if (request.oldText.isEmpty() || request.newText.isEmpty()) return result
            val origin = Origin(Subject.Region("nowhere"), TextRange(0, 1), TextRange(0, 1))
            val added = ChangeNode(Subject.Region("added"), null, TextRange(0, request.newText.length), Presence.ADDED, extractedFrom = listOf(origin))
            return result.copy(changes = result.changes + added)
        }
    }

    private class Throwing : Delegating() {
        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult =
            if (request.oldText.contains("int area(int side {") || request.newText.contains("int area(int side {")) error("cannot parse")
            else super.compare(request, cancellation)
    }
}
