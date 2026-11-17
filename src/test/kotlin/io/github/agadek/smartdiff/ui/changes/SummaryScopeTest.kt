package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import io.github.agadek.smartdiff.ui.changes.ReviewTreeFixtures.path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The summary scopes of [ChangesSummaryService]: one per tree, sharing one background thread (changes-tree/07). */
class SummaryScopeTest : BasePlatformTestCase() {

    private val service get() = ChangesSummaryService.getInstance(project)
    private var saved: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        saved = SmartDiffSettings.getInstance().state
        SmartDiffSettings.getInstance().loadState(SmartDiffSettings.SettingsState())
        service.waitForSummaries()
        service.clear()
        ReviewTreeFixtures.maskEngines(testRootDisposable, InstantReviewEngine())
    }

    override fun tearDown() {
        try {
            saved?.let(SmartDiffSettings.getInstance()::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun scope(maxExtra: Int = ChangesSummaryService.MAX_EXTRA_ENTRIES) =
        service.createScope(testRootDisposable, refresh = {}, maxExtraEntries = maxExtra)

    private fun revisions(change: Change) = listOf(change.beforeRevision, change.afterRevision).map { it as CountingRevision }

    private fun reads(change: Change) = revisions(change).sumOf { it.reads }

    fun `test summaries are computed in the background and never read content on the UI thread`() {
        // changes-tree/07 R16
        val scope = scope()
        val change = ReviewTreeFixtures.modified("Cart.java")

        val first = com.intellij.openapi.application.ApplicationManager.getApplication().let { app ->
            var result: List<FileSummary?>? = null
            app.invokeAndWait { result = service.summaries(listOf(change), scope) }
            result!!
        }
        assertNull("the first call only queues the file", first.single())
        service.waitForSummaries()

        assertNotNull(service.summaries(listOf(change), scope).single())
        assertFalse("no revision was read on the UI thread", revisions(change).any { it.readOnEdt })
        assertEquals("each side was read once", 2, reads(change))
    }

    fun `test pending files that left the tree are not computed`() {
        // changes-tree/07 R17
        val scope = scope()
        val gate = CountDownLatch(1)
        val blocker = Change(
            CountingRevision("a", path("Block.java"), "1") { gate.await(10, TimeUnit.SECONDS) },
            CountingRevision("b", path("Block.java"), "2"),
        )
        val left = ReviewTreeFixtures.modified("Left.java")
        val stays = ReviewTreeFixtures.modified("Stays.java")

        service.summaries(listOf(blocker, left, stays), scope)
        // Another commit is selected while the first file is still being compared.
        service.summaries(listOf(blocker, stays), scope)
        gate.countDown()
        service.waitForSummaries()

        assertEquals("the file that left the tree was not read", 0, reads(left))
        assertTrue("the file still in the tree was computed", reads(stays) > 0)
        assertNotNull(service.summaries(listOf(blocker, stays), scope)[1])
    }

    fun `test a commit selected again shows its summaries without comparing again`() {
        // changes-tree/07 R18
        val scope = scope()
        val first = ReviewTreeFixtures.modified("First.java")
        val second = ReviewTreeFixtures.modified("Second.java")
        service.summaries(listOf(first), scope)
        service.waitForSummaries()
        service.summaries(listOf(second), scope)
        service.waitForSummaries()
        val readsBefore = reads(first)

        val started = System.nanoTime()
        val again = service.summaries(listOf(first), scope)

        assertNotNull("the summary comes from the cache", again.single())
        assertEquals("the file was not compared again", readsBefore, reads(first))
        assertTrue("within 1 s", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
    }

    fun `test a scope keeps a bounded number of earlier files`() {
        // changes-tree/07 R18
        val scope = scope(maxExtra = 1)
        val files = (1..3).map { ReviewTreeFixtures.modified("F$it.java") }
        for (file in files) {
            service.summaries(listOf(file), scope)
            service.waitForSummaries()
        }

        assertEquals("the current file and one earlier file", 2, scope.size())
        val readsBefore = reads(files[0])
        assertNull("the oldest file was evicted", service.summaries(listOf(files[0]), scope).single())
        service.waitForSummaries()
        assertTrue("it is compared again", reads(files[0]) > readsBefore)
    }

    fun `test a review tree never removes the summaries of the Changes tree or another review tree`() {
        // changes-tree/07 R19
        val local = ReviewTreeFixtures.modified("Local.java")
        val logFile = ReviewTreeFixtures.modified("Log.java")
        val requestFile = ReviewTreeFixtures.modified("Request.java")
        val log = scope()
        val request = scope()
        service.summaries(listOf(local))
        service.summaries(listOf(logFile), log)
        service.summaries(listOf(requestFile), request)
        service.waitForSummaries()

        service.summaries(emptyList(), log)
        service.summaries(listOf(ReviewTreeFixtures.modified("Other.java")), request)

        assertNotNull("the Changes tree keeps its summary", service.summaries(listOf(local)).single())
        val readsBefore = reads(logFile)
        assertNotNull("the log scope still has its earlier file", service.summaries(listOf(logFile), log).single())
        assertEquals(readsBefore, reads(logFile))
    }

    fun `test a disposed scope skips its queued files`() {
        // changes-tree/07 R17, Edge cases: a tree disposed while summaries are pending
        val parent = Disposer.newDisposable()
        val scope = service.createScope(parent, refresh = {})
        val gate = CountDownLatch(1)
        val blocker = Change(
            CountingRevision("a", path("Block.java"), "1") { gate.await(10, TimeUnit.SECONDS) },
            CountingRevision("b", path("Block.java"), "2"),
        )
        val queued = ReviewTreeFixtures.modified("Queued.java")
        service.summaries(listOf(blocker, queued), scope)

        Disposer.dispose(parent)
        gate.countDown()
        service.waitForSummaries()

        assertEquals(0, reads(queued))
        assertTrue(scope.isDisposed)
        assertEquals(listOf<FileSummary?>(null), service.summaries(listOf(queued), scope))
    }
}
