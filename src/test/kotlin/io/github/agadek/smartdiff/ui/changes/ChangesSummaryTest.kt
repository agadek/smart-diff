package io.github.agadek.smartdiff.ui.changes

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.contents.DocumentContent
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
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesViewModifier
import com.intellij.openapi.vcs.changes.ChangesViewNodeAction
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProvider
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.NoneChangesGroupingFactory
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.util.ThreeState
import com.intellij.util.ui.UIUtil
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
import java.util.concurrent.TimeUnit

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
        // changes-tree/03-coverage-and-scale R8
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
        settings.outlineMinimised = true
        panel.setRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("no diff viewer was created", { viewers.isNotEmpty() }, 10)
        val outline = OutlineController.of(viewers.last())!!
        PlatformTestUtil.waitWithEventsDispatching({ "no outline; state is ${outline.state}" }, { outline.state is OutlineState.Shown }, 10)

        assertTrue("the outline is shown even when it is off by default", outline.isOutlineVisible)
        assertFalse("a remembered minimised state does not hide the selected row", outline.isMinimised)
        assertTrue("the full panel is shown for the selected row", UIUtil.isAncestor(viewers.last().component, outline.panel))
        assertEquals("method isEmpty()  added", outline.panel.selectedItem()?.label)
        assertNull("the request selects the row once", request.getUserData(OutlinePath.KEY))
    }

    fun `test a declaration click opens the file's diff without context when the diff action cannot be loaded`() {
        // changes-tree/02-changes-tree-declarations R16
        val change = modified("Cart.java", OLD_CART, NEW_CART)
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val added = summaryNode(change)!!.preorderEnumeration().toList().filterIsInstance<DeclarationNode>().last()
        val chains = mutableListOf<DiffRequestChain>()
        val recording = Disposer.newDisposable(testRootDisposable)
        ApplicationManager.getApplication().replaceService(DiffManager::class.java, RecordingDiffManager(shownChains = chains), recording)
        val action = DeclarationNodeAction(project) { p, changes, index, key, value ->
            ChangesDiff.show(p, changes, index, key, value, "smartdiff.test.MissingAction", "smartdiff.test.MissingContext")
        }

        assertTrue("the fallback opens the file's diff", action.handleDoubleClick(added))
        Disposer.dispose(recording)

        val request = process(chains.single())
        assertNull("the fallback has no declaration context", request.getUserData(OutlinePath.KEY))
        assertTrue("the request is the file's own diff", request is SimpleDiffRequest)
        val contents = (request as SimpleDiffRequest).contents.map { it as DocumentContent }
        assertEquals(listOf(OLD_CART, NEW_CART), contents.map { it.document.text })
        assertTrue("the title names the file", request.title.orEmpty().contains("Cart.java"))
    }

    fun `test the fallback loads revision content off the EDT`() {
        // changes-tree/02-changes-tree-declarations R16
        val path = VcsUtil.getFilePath("/repo/Cart.java", false)
        val before = CheckedRevision(OLD_CART, path)
        val after = CheckedRevision(NEW_CART, path)
        val change = Change(before, after)
        val chains = mutableListOf<DiffRequestChain>()
        val recording = Disposer.newDisposable(testRootDisposable)
        ApplicationManager.getApplication().replaceService(DiffManager::class.java, RecordingDiffManager(shownChains = chains), recording)

        // The click runs on the EDT; the fallback must only build the lazy request there.
        var shown = false
        ApplicationManager.getApplication().invokeAndWait {
            shown = ChangesDiff.show(
                project,
                listOf(change),
                0,
                OutlinePath.KEY,
                "class Cart",
                "smartdiff.test.MissingAction",
                "smartdiff.test.MissingContext",
            )
        }
        Disposer.dispose(recording)

        assertTrue("the fallback opens the file's diff", shown)
        assertFalse("opening the diff does not read the revisions", before.read || after.read)
        val request = process(chains.single())
        assertTrue("the diff window reads both revisions", before.read && after.read)
        val contents = (request as SimpleDiffRequest).contents.map { it as DocumentContent }
        assertEquals(listOf(OLD_CART, NEW_CART), contents.map { it.document.text })
    }

    fun `test the diff action is looked up through the platform and plugin classloaders`() {
        val loaders = ChangesDiff.classLoaders()

        assertSame("the Changes view's platform loader is tried first", ChangesViewModifier::class.java.classLoader, loaders.first())
        assertTrue("the plugin's loader is next", loaders.contains(ChangesDiff::class.java.classLoader))
    }

    fun `test the version of a local file is its modification stamp`() {
        val file = myFixture.addFileToProject("Local.java", "class Local {}").virtualFile
        val revision = SimpleContentRevision("class Local {}", path(file), VcsRevisionNumber.NULL)

        val version = ChangesSummaryService.version(revision)

        assertTrue("a local file has a state to compare: $version", version.startsWith("local:"))
        assertFalse("a local file is not just its missing state", version == "local")
    }

    fun `test the version of a deleted local file is local`() {
        val file = myFixture.addFileToProject("Deleted.java", "class Deleted {}").virtualFile
        ApplicationManager.getApplication().runWriteAction { file.delete(testRootDisposable) }
        assertFalse(file.isValid)
        val revision = SimpleContentRevision("class Deleted {}", path(file), VcsRevisionNumber.NULL)

        assertEquals("local", ChangesSummaryService.version(revision))
    }

    fun `test the summary is the outline's summary line, or the reason it was not analyzed`() {
        // changes-tree/03-coverage-and-scale R7; outline/05-analysis-fallback R7
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
        // changes-tree/03-coverage-and-scale R10
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

        // The reason stays visible while one retry is queued after the delay.
        Thread.sleep(ChangesSummaryService.RETRY_DELAY_MILLIS + 100)
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        assertEquals("a timeout retries once", 2, engine.calls)

        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        assertEquals("the retry is not repeated", 2, engine.calls)
    }

    fun `test added and deleted files and other languages get no summary`() {
        // changes-tree/03-coverage-and-scale R6; outline/05-analysis-fallback R8
        val added = Change(null, SimpleContentRevision(NEW_CART, VcsUtil.getFilePath("/src/New.java", false), "2"))
        val deleted = Change(SimpleContentRevision(OLD_CART, VcsUtil.getFilePath("/src/Old.java", false), "1"), null)
        val text = modified("notes.txt", "a\n", "b\n")

        listOf(added, deleted, text).forEach { summaryNode(it, listOf(added, deleted, text)) }
        ChangesSummaryService.getInstance(project).waitForSummaries()

        listOf(added, deleted, text).forEach { assertNull(summaryNode(it, listOf(added, deleted, text))) }
    }

    fun `test no summary is shown when the setting is off`() {
        // changes-tree/01-changes-tree-summary R12; changes-tree/04-hide-and-filter R3
        val change = modified("Cart.java", OLD_CART, NEW_CART)
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()

        settings.showChangesSummary = false
        val node = summaryNode(change)

        assertTrue("the row has no summary text", node == null || node.summary.isEmpty())
        if (node != null) {
            val renderer = ChangesBrowserNodeRenderer(project, BooleanGetter { false }, false)
            node.render(renderer, false, false, false)
            assertFalse("no summary or reason is drawn", renderer.getCharSequence(false).toString().contains("changed"))
            assertTrue("the declaration rows are still there", node.childCount > 0)
        }
    }

    fun `test the first 500 file nodes are summarized and the rest keep the platform row`() {
        // changes-tree/03-coverage-and-scale R1, R2, R3
        maskEngines(InstantEngine())
        val changes = files(ChangesSummaryModifier.MAX_FILES + 1)

        tree(changes)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val (_, nodes) = tree(changes)

        assertEquals(changes.size, nodes.size)
        assertTrue(
            "the first 500 file nodes are summarized",
            nodes.take(ChangesSummaryModifier.MAX_FILES).all { it is SummaryChangeNode },
        )
        assertTrue("the file after the limit keeps the platform's row", nodes.last() !is SummaryChangeNode)
        assertTrue("the file after the limit has no declaration rows", nodes.last().isLeaf && nodes.last().childCount == 0)
    }

    fun `test a rebuild summarizes the same 500 file nodes`() {
        // changes-tree/03-coverage-and-scale R4
        maskEngines(InstantEngine())
        val changes = files(ChangesSummaryModifier.MAX_FILES + 2)

        tree(changes)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val first = summarized(tree(changes).second)
        val second = summarized(tree(changes).second)

        assertEquals(ChangesSummaryModifier.MAX_FILES, first.size)
        assertEquals(first, second)
    }

    fun `test a file after the limit stays in the tree and opens like the platform's row`() {
        // changes-tree/03-coverage-and-scale R9
        maskEngines(InstantEngine())
        val changes = files(ChangesSummaryModifier.MAX_FILES + 1)

        tree(changes)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val (root, nodes) = tree(changes)
        val afterLimit = nodes.drop(ChangesSummaryModifier.MAX_FILES)
        assertTrue("the tree has a file after the limit", afterLimit.isNotEmpty())
        val last = afterLimit.first()
        val change = last.userObject as Change

        assertFalse("the file after the limit keeps the platform's row", last is SummaryChangeNode)
        assertTrue("the file is still selectable and includable", change in VcsTreeModelData.allUnder(root).userObjects())
        assertFalse("the platform's own action opens it, not the declaration action", DeclarationNodeAction(project).handleDoubleClick(last))
    }

    private fun maskEngines(vararg engines: ComparisonEngine) {
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, engines.map(::factory), testRootDisposable)
    }

    private fun files(count: Int): List<Change> = (1..count).map { modified("File$it.java", OLD_CART, NEW_CART) }

    /**
     * Builds the tree for [changes] as the Changes view does. The file nodes are taken before the platform sorts
     * the model, in the depth-first order the modifier walks.
     */
    private fun tree(changes: List<Change>): Pair<ChangesBrowserNode<*>, List<ChangesBrowserChangeNode>> {
        val builder = TreeModelBuilder(project, NoneChangesGroupingFactory).setChanges(changes, null)
        ChangesSummaryModifier(project).modifyTreeModelBuilder(builder)
        val nodes = builder.myRoot.depthFirstEnumeration().toList().filterIsInstance<ChangesBrowserChangeNode>()
        builder.build()
        return builder.myRoot to nodes
    }

    /** Runs the lazy request on a pool thread, as the diff window does. */
    private fun process(chain: DiffRequestChain): DiffRequest {
        val producer = chain.requests.single()
        return ApplicationManager.getApplication()
            .executeOnPooledThread<DiffRequest> { producer.process(UserDataHolderBase(), EmptyProgressIndicator()) }
            .get(10, TimeUnit.SECONDS)
    }

    private fun summarized(nodes: List<ChangesBrowserChangeNode>): List<String> =
        nodes.filterIsInstance<SummaryChangeNode>()
            .map { (it.userObject as Change).afterRevision!!.file.name }

    /** An engine that analyzes every file at once, so a large tree needs no real comparison. */
    private class InstantEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.instant")
        override val displayName = "Instant (test)"
        override val version = "1"

        override fun supports(languageId: String) = true

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult =
            ComparisonResult(id, version, Status.ANALYZED)
    }

    private fun modified(fileName: String, oldText: String, newText: String): Change {
        val path = VcsUtil.getFilePath("/repo/$fileName", false)
        return Change(SimpleContentRevision(oldText, path, "1"), SimpleContentRevision(newText, path, "2"))
    }

    /** A path that resolves to [file], which the local path of a light test file does not. */
    private fun path(file: VirtualFile): FilePath =
        object : FilePath by VcsUtil.getFilePath(file.path, false) {
            override fun getVirtualFile(): VirtualFile = file
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
    private class RecordingDiffManager(
        private val shownChains: MutableList<DiffRequestChain> = mutableListOf(),
        private val shownRequests: MutableList<DiffRequest> = mutableListOf(),
    ) : DiffManager() {

        override fun showDiff(project: Project?, request: DiffRequest) {
            shownRequests += request
        }

        override fun showDiff(project: Project?, request: DiffRequest, hints: DiffDialogHints) {
            shownRequests += request
        }

        override fun showDiff(project: Project?, requests: DiffRequestChain, hints: DiffDialogHints) {
            shownChains += requests
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

/** A revision whose content can only be read off the EDT, as a committed revision in the Changes view is. */
internal class CheckedRevision(private val text: String, file: FilePath) : ContentRevision {
    private val delegate = SimpleContentRevision(text, file, "1")

    @Volatile
    var read = false

    override fun getContent(): String {
        if (ApplicationManager.getApplication().isDispatchThread) {
            throw AssertionError("revision content is read on the EDT")
        }
        read = true
        return text
    }

    override fun getFile(): FilePath = delegate.file

    override fun getRevisionNumber(): VcsRevisionNumber = delegate.revisionNumber
}
