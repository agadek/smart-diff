package io.github.agadek.smartdiff.ui.changes

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.BooleanGetter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesViewModifier
import com.intellij.openapi.vcs.changes.ChangesViewNodeAction
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProvider
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.NoneChangesGroupingFactory
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.util.ThreeState
import com.intellij.vcsUtil.VcsUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import io.github.agadek.smartdiff.ui.OutlineController
import io.github.agadek.smartdiff.ui.OutlinePath
import io.github.agadek.smartdiff.ui.OutlineState
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import java.awt.Window

/** The structural summary after each file in the Local Changes and Commit tree, and its declarations under it. */
class ChangesSummaryTest : BasePlatformTestCase() {

    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        saved = settings.state
        settings.loadState(SmartDiffSettings.SettingsState())
        // The light project, and so the summary cache, is shared by the tests.
        ChangesSummaryService.getInstance(project).run {
            waitForSummaries()
            clear()
        }
    }

    override fun tearDown() {
        try {
            saved?.let(settings::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test the modifier is registered for the Changes view`() {
        assertTrue(ChangesViewModifier.KEY.getExtensions(project).any { it is ChangesSummaryModifier })
    }

    fun `test a changed file shows its summary once it is computed`() {
        val change = modified("Cart.java", OLD_CART, NEW_CART)

        assertNull("the first tree is built before the summary is known", summaryNode(change))
        ChangesSummaryService.getInstance(project).waitForSummaries()

        val node = summaryNode(change)
        assertNotNull("the next tree shows the summary", node)
        assertEquals("1 changed · 1 added", node!!.summary)
        assertSame(change, node.userObject)
    }

    fun `test the row shows the file name and then the summary`() {
        val change = modified("Cart.java", OLD_CART, NEW_CART)
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val node = summaryNode(change)!!

        val renderer = ChangesBrowserNodeRenderer(project, BooleanGetter { false }, false)
        node.render(renderer, false, false, false)

        val text = renderer.getCharSequence(false).toString()
        assertTrue(text, text.startsWith("Cart.java"))
        assertTrue(text, text.endsWith("1 changed · 1 added"))
    }

    fun `test a changed file lists its outline rows under it, which select no change`() {
        val change = modified("Cart.java", OLD_CART, NEW_CART)
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val node = summaryNode(change)!!

        assertEquals(listOf("class Cart", "  method total()  changed", "  method isEmpty()  added"), rows(node))
        assertFalse("a file starts collapsed", node.shouldExpandByDefault())
        val declarations = node.preorderEnumeration().toList().filterIsInstance<DeclarationNode>()
        assertTrue(declarations.none { it.isMeaningfulNode })
        assertEquals("the file's checkbox and selection count only the change", listOf(change), VcsTreeModelData.allUnder(node).userObjects())
        assertSame(node, declarations.last().fileNode)
        assertEquals("class Cart / method isEmpty()", declarations.last().path.toString())
    }

    fun `test a declaration row is drawn as the outline draws it`() {
        val change = modified("Cart.java", OLD_CART, NEW_CART)
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val added = summaryNode(change)!!.preorderEnumeration().toList().filterIsInstance<DeclarationNode>().last()

        val renderer = ChangesBrowserNodeRenderer(project, BooleanGetter { false }, false)
        added.render(renderer, false, false, false)

        assertEquals("method isEmpty()  added", renderer.getCharSequence(false).toString())
        assertEquals("method isEmpty()  added", added.textPresentation)
    }

    fun `test opening a declaration shows the file's diff with the declaration selected in the outline`() {
        assertTrue(ChangesViewNodeAction.EP_NAME.getExtensions(project).any { it is DeclarationNodeAction })
        val change = modified("Cart.java", OLD_CART, NEW_CART)
        val other = modified("Other.java", OLD_CART, NEW_CART)
        summaryNode(change, listOf(change, other))
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val node = summaryNode(change, listOf(change, other))!!
        val added = node.preorderEnumeration().toList().filterIsInstance<DeclarationNode>().last()
        val action = DeclarationNodeAction(project)
        val shown = mutableListOf<DiffRequestChain>()
        // Only while the action runs: the diff panel itself needs the platform's own DiffManager.
        val recording = Disposer.newDisposable(testRootDisposable)
        ApplicationManager.getApplication().replaceService(DiffManager::class.java, RecordingDiffManager(shown), recording)
        // The platform's request has titles that map the light project's directory to disk, which it is not.
        ExtensionTestUtil.addExtensions(ChangeDiffRequestProvider.EP_NAME, listOf(UntitledRequests(listOf(change, other))), testRootDisposable)
        val viewers = mutableListOf<FrameDiffTool.DiffViewer>()
        ExtensionTestUtil.addExtensions(DiffExtension.EP_NAME, listOf(object : DiffExtension() {
            override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
                viewers += viewer
            }
        }), testRootDisposable)

        assertFalse("a file row is left to the platform", action.handleDoubleClick(node))
        assertTrue(action.handleDoubleClick(added))
        Disposer.dispose(recording)

        val chain = shown.single()
        assertEquals("the other changes of the tree are next to it", 2, chain.requests.size)
        assertEquals(0, chain.index)
        val request = chain.requests[chain.index].process(UserDataHolderBase(), EmptyProgressIndicator())
        val panel = DiffManager.getInstance().createRequestPanel(project, testRootDisposable, null)
        settings.showOutline = false
        panel.setRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("no diff viewer was created", { viewers.isNotEmpty() }, 10)
        val outline = OutlineController.of(viewers.last())!!
        PlatformTestUtil.waitWithEventsDispatching({ "no outline; state is ${outline.state}" }, { outline.state is OutlineState.Shown }, 10)

        assertTrue("the outline is shown even when it is off by default", outline.isOutlineVisible)
        assertEquals("method isEmpty()  added", outline.panel.selectedItem()?.label)
        assertNull("the request selects the row once", request.getUserData(OutlinePath.KEY))
    }

    fun `test the summary is the outline's summary line, or the reason it was not analyzed`() {
        val engine = EngineId("test")
        fun failed(reason: String?) = ComparisonResult(engine, "1", Status.FAILED, fallbackReason = reason)

        assertEquals("no structural changes", ChangesSummaryService.summary(ComparisonResult(engine, "1", Status.ANALYZED)))
        assertEquals("not analyzed: too large", ChangesSummaryService.summary(ComparisonResult(engine, "1", Status.TOO_LARGE)))
        assertEquals("not analyzed: timed out", ChangesSummaryService.summary(failed("timed out after 100 ms")))
        assertEquals("not analyzed: parse errors", ChangesSummaryService.summary(failed("the old side could not be parsed")))
        assertEquals("not analyzed: analysis failed", ChangesSummaryService.summary(failed("engine test failed: boom")))
        assertEquals("not analyzed: analysis failed", ChangesSummaryService.summary(failed(null)))
        assertNull(ChangesSummaryService.summary(ComparisonResult(engine, "1", Status.UNSUPPORTED)))
    }

    fun `test a file over the size limit says it was not analyzed`() {
        settings.limits = Limits.DEFAULT.copy(maxChars = 10)
        val change = modified("Cart.java", OLD_CART, NEW_CART)

        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()

        val node = summaryNode(change)!!
        assertEquals("not analyzed: too large", node.summary)
        assertEquals(0, node.childCount)
        assertTrue("no declaration rows, so no expand arrow", node.isLeaf)
    }

    fun `test a file over the time limit says it timed out`() {
        val engine = SlowEngine()
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(factory(engine)), testRootDisposable)
        settings.limits = Limits.DEFAULT.copy(timeoutMillis = 100)
        val change = modified("Cart.java", OLD_CART, NEW_CART)

        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()

        val node = summaryNode(change)!!
        assertEquals("not analyzed: timed out", node.summary)
        assertEquals(0, node.childCount)
        assertTrue("no declaration rows, so no expand arrow", node.isLeaf)
        assertTrue("the engine was canceled", engine.canceled)
        assertEquals(1, engine.calls)

        // A timed-out summary is shown once and retried on the next tree build.
        summaryNode(change)
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        assertTrue("a timeout is retried instead of being cached forever", engine.calls >= 2)
    }

    fun `test added and deleted files and other languages get no summary`() {
        val added = Change(null, SimpleContentRevision(NEW_CART, VcsUtil.getFilePath("/src/New.java", false), "2"))
        val deleted = Change(SimpleContentRevision(OLD_CART, VcsUtil.getFilePath("/src/Old.java", false), "1"), null)
        val text = modified("notes.txt", "a\n", "b\n")

        listOf(added, deleted, text).forEach { summaryNode(it, listOf(added, deleted, text)) }
        ChangesSummaryService.getInstance(project).waitForSummaries()

        listOf(added, deleted, text).forEach { assertNull(summaryNode(it, listOf(added, deleted, text))) }
    }

    fun `test no summary is shown when the setting is off`() {
        val change = modified("Cart.java", OLD_CART, NEW_CART)
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()

        settings.showChangesSummary = false

        assertNull(summaryNode(change))
    }

    private fun modified(fileName: String, oldText: String, newText: String): Change {
        val path = VcsUtil.getFilePath("/repo/$fileName", false)
        return Change(SimpleContentRevision(oldText, path, "1"), SimpleContentRevision(newText, path, "2"))
    }

    /** Builds the tree for [changes] as the Changes view does and returns the row of [change] if it has a summary. */
    private fun summaryNode(change: Change, changes: List<Change> = listOf(change)): SummaryChangeNode? {
        val builder = TreeModelBuilder(project, NoneChangesGroupingFactory).setChanges(changes, null)
        ChangesSummaryModifier(project).modifyTreeModelBuilder(builder)
        builder.build()
        val all = builder.myRoot.depthFirstEnumeration().toList().filterIsInstance<ChangesBrowserChangeNode>()
        assertEquals("every change keeps one row", changes.size, all.size)
        return all.filterIsInstance<SummaryChangeNode>().firstOrNull { it.userObject === change }
    }

    /** The rows under [node], indented by depth. */
    private fun rows(node: SummaryChangeNode): List<String> {
        val rows = mutableListOf<String>()
        fun visit(parent: ChangesBrowserNode<*>, depth: Int) {
            for (child in parent.children().toList()) {
                child as DeclarationNode
                rows += "  ".repeat(depth) + child.item.label
                visit(child, depth + 1)
            }
        }
        visit(node, 0)
        return rows
    }

    /** A factory for an engine that is not in the plugin's own extensions. */
    private fun factory(engine: ComparisonEngine) = object : ComparisonEngineFactory {
        override fun create(project: Project?): ComparisonEngine = engine
    }

    /** Never finishes; it checks for cancellation until it is canceled. */
    private class SlowEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.slow")
        override val displayName = "Slow (test)"
        override val version = "1"

        @Volatile
        var canceled = false

        @Volatile
        var calls = 0

        override fun supports(languageId: String) = true

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
            calls++
            while (true) {
                try {
                    cancellation.checkCanceled()
                } catch (e: Throwable) {
                    canceled = true
                    throw e
                }
                Thread.sleep(5)
            }
        }
    }

    /** The platform's diff request for [changes], without the titles that need a project on disk. */
    private class UntitledRequests(private val changes: List<Change>) : ChangeDiffRequestProvider {
        override fun isEquals(change1: Change, change2: Change): ThreeState = ThreeState.UNSURE
        override fun canCreate(project: Project?, change: Change): Boolean = changes.any { it === change }
        override fun process(presentable: ChangeDiffRequestProducer, context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
            val change = presentable.change
            val project = presentable.project
            return SimpleDiffRequest(
                presentable.name,
                ChangeDiffRequestProducer.createContent(project, change.beforeRevision, context, indicator),
                ChangeDiffRequestProducer.createContent(project, change.afterRevision, context, indicator),
                "old",
                "new",
            )
        }
    }

    /** Records the diffs the platform's diff action shows instead of opening windows. */
    private class RecordingDiffManager(private val shown: MutableList<DiffRequestChain>) : DiffManager() {

        override fun showDiff(project: Project?, request: DiffRequest) { error("unexpected") }
        override fun showDiff(project: Project?, request: DiffRequest, hints: DiffDialogHints) { error("unexpected") }
        override fun showDiff(project: Project?, requests: DiffRequestChain, hints: DiffDialogHints) {
            shown += requests
        }
        override fun createRequestPanel(project: Project?, parent: Disposable, window: Window?): DiffRequestPanel = error("unexpected")
        override fun showMerge(project: Project?, request: MergeRequest) { error("unexpected") }
    }

    private companion object {
        val OLD_CART = """
            // cart
            class Cart {
                int total() {
                    return 1;
                }
            }
        """.trimIndent()

        val NEW_CART = """
            // cart
            class Cart {
                int total() {
                    return 2;
                }

                boolean isEmpty() {
                    return total() == 0;
                }
            }
        """.trimIndent()
    }
}
