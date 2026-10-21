package io.github.agadek.smartdiff.engine.api.contract

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
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

    private fun result(vararg changes: ChangeNode) = ComparisonResult(EngineId("t"), "1", Status.ANALYZED, changes = changes.toList())

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

    private class Throwing : Delegating() {
        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult =
            if (request.oldText.contains("int area(int side {") || request.newText.contains("int area(int side {")) error("cannot parse")
            else super.compare(request, cancellation)
    }
}
