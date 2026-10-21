package io.github.agadek.smartdiff.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class SafeComparisonTest {

    private class FixedEngine(private val outcome: (ComparisonRequest) -> ComparisonResult) : ComparisonEngine {
        var calls = 0
        override val id = EngineId("test.fixed")
        override val displayName = "Fixed"
        override val version = "1"
        override fun supports(languageId: String) = true
        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
            calls++
            return outcome(request)
        }
    }

    private fun request(old: String = "a", new: String = "b", maxChars: Int = 10) =
        ComparisonRequest(old, new, "TEXT", limits = Limits(maxChars, 100, 1_000))

    @Test
    fun `returns the engine's result`() {
        val engine = FixedEngine { ComparisonResult(EngineId("test.fixed"), "1", Status.ANALYZED) }
        assertEquals(Status.ANALYZED, compareSafely(engine, request(), Cancellation.NONE).status)
    }

    @Test
    fun `a side over the size limit is too large without calling the engine`() {
        val engine = FixedEngine { error("not called") }
        val result = compareSafely(engine, request(new = "x".repeat(11)), Cancellation.NONE)
        assertEquals(Status.TOO_LARGE, result.status)
        assertEquals(0, engine.calls)
        assertEquals(engine.id, result.engineId)
    }

    @Test
    fun `an exception becomes a failed result`() {
        val engine = FixedEngine { throw IllegalStateException("boom") }
        val result = compareSafely(engine, request(), Cancellation.NONE)
        assertEquals(Status.FAILED, result.status)
        assertTrue(result.fallbackReason!!.contains("boom"))
    }

    @Test
    fun `a stack overflow becomes a failed result`() {
        val engine = FixedEngine { throw StackOverflowError() }
        assertEquals(Status.FAILED, compareSafely(engine, request(), Cancellation.NONE).status)
    }

    @Test
    fun `cancellation propagates`() {
        val canceled = CancellationException("canceled")
        val engine = FixedEngine { throw canceled }
        assertSame(canceled, assertThrows(CancellationException::class.java) { compareSafely(engine, request(), Cancellation.NONE) })
    }

    @Test
    fun `a node is reported when it or a descendant is a change`() {
        val unchanged = ChangeNode(Subject.Region("u"), TextRange(0, 1), TextRange(0, 1), Presence.MATCHED)
        val changed = unchanged.copy(content = Content.FORMATTING_ONLY)
        assertFalse(unchanged.isReported)
        assertTrue(changed.isReported)
        assertTrue(unchanged.copy(moved = true).isReported)
        assertTrue(unchanged.copy(presence = Presence.ADDED, oldRange = null).isReported)
        val container = unchanged.copy(children = listOf(unchanged, changed))
        assertTrue(container.isReported)
        assertFalse(container.isChangedItself)
        assertFalse(unchanged.copy(children = listOf(unchanged)).isReported)
    }

    @Test
    fun `a result has reported changes only for reported nodes, the file region or unanalyzed ranges`() {
        val unchanged = ChangeNode(Subject.Region("u"), TextRange(0, 1), TextRange(0, 1), Presence.MATCHED)
        val base = ComparisonResult(EngineId("t"), "1", Status.ANALYZED, changes = listOf(unchanged))
        assertFalse(base.hasReportedChanges)
        assertTrue(base.copy(fileRegion = unchanged.copy(content = Content.CHANGED)).hasReportedChanges)
        assertTrue(base.copy(status = Status.PARTIAL, unanalyzed = listOf(RangePair(TextRange(0, 1), null))).hasReportedChanges)
    }

    @Test
    fun `ranges and limits reject invalid values`() {
        assertThrows(IllegalArgumentException::class.java) { TextRange(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { TextRange(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { RangePair(null, null) }
        assertThrows(IllegalArgumentException::class.java) { Limits(1, 1, 0) }
    }
}
