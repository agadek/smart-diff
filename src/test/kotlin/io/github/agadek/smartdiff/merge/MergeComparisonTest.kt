package io.github.agadek.smartdiff.merge

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.Status
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Both runs of a merge (three-way merge plan §5.3, §5.4), with an engine that records its requests. */
class MergeComparisonTest {

    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `each run compares the base with one side and passes the other side as a related text`() {
        val requests = Collections.synchronizedList(mutableListOf<ComparisonRequest>())
        val engine = engine { request, _ ->
            requests += request
            ComparisonResult(id, version, Status.ANALYZED)
        }

        val joined = MergeComparison.compare(engine, MergeTexts("base", "left", "right", "JAVA", "A.java"), Cancellation.NONE, executor)

        assertTrue(joined.isAnalyzed(MergeSide.LEFT) && joined.isAnalyzed(MergeSide.RIGHT))
        val byNewText = requests.associateBy { it.newText.toString() }
        assertEquals(setOf("left", "right"), byNewText.keys)
        assertEquals(listOf("right"), byNewText.getValue("left").relatedTexts.map(CharSequence::toString))
        assertEquals(listOf("left"), byNewText.getValue("right").relatedTexts.map(CharSequence::toString))
        assertTrue(requests.all { it.oldText == "base" && it.languageId == "JAVA" && it.fileName == "A.java" })
    }

    @Test
    fun `a side over the size limit is too large and the other side is still analyzed`() {
        val engine = engine { _, _ -> ComparisonResult(id, version, Status.ANALYZED) }
        val texts = MergeTexts("base", "a much longer left side", "right", "JAVA", limits = Limits(maxChars = 10, maxDeclarations = 10, timeoutMillis = 1000))

        val joined = MergeComparison.compare(engine, texts, Cancellation.NONE, executor)

        assertEquals(Status.TOO_LARGE, joined.left.status)
        assertEquals(Status.ANALYZED, joined.right.status)
    }

    @Test
    fun `canceling one run cancels the other`() {
        val rightStarted = CountDownLatch(1)
        val rightCanceled = CountDownLatch(1)
        val engine = engine { request, cancellation ->
            if (request.newText == "left") {
                rightStarted.await(5, TimeUnit.SECONDS)
                throw CancellationException("left canceled")
            }
            rightStarted.countDown()
            try {
                while (true) {
                    cancellation.checkCanceled()
                    Thread.sleep(5)
                }
                @Suppress("UNREACHABLE_CODE")
                ComparisonResult(id, version, Status.ANALYZED)
            } catch (e: CancellationException) {
                rightCanceled.countDown()
                throw e
            }
        }

        try {
            MergeComparison.compare(engine, MergeTexts("base", "left", "right", "JAVA"), Cancellation.NONE, executor)
            fail("expected the merge comparison to be canceled")
        } catch (_: CancellationException) {
        }
        assertTrue("the right run was not canceled", rightCanceled.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `the host's cancellation reaches both runs`() {
        val canceled = AtomicBoolean()
        val host = Cancellation { if (canceled.get()) throw CancellationException("time limit") }
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val engine = engine { request, cancellation ->
            calls += request.newText.toString()
            canceled.set(true)
            cancellation.checkCanceled()
            ComparisonResult(id, version, Status.ANALYZED)
        }

        try {
            MergeComparison.compare(engine, MergeTexts("base", "left", "right", "JAVA"), host, executor)
            fail("expected the merge comparison to be canceled")
        } catch (_: CancellationException) {
        }
        assertFalse(calls.isEmpty())
    }

    private fun engine(compare: ComparisonEngine.(ComparisonRequest, Cancellation) -> ComparisonResult) = object : ComparisonEngine {
        override val id = EngineId("test")
        override val displayName = "Test"
        override val version = "1"
        override fun supports(languageId: String) = true
        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult = compare(this, request, cancellation)
    }
}
