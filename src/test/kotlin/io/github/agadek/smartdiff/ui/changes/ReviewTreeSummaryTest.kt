package io.github.agadek.smartdiff.ui.changes

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.util.BooleanGetter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.ui.ChangeType
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import io.github.agadek.smartdiff.ui.changes.ReviewTreeFixtures.modified
import io.github.agadek.smartdiff.ui.changes.ReviewTreeFixtures.path
import javax.swing.tree.TreePath

/**
 * Summaries in review trees (changes-tree/07): a real changes tree whose model is set through `updateTreeModel`, as the
 * Git log and pull request trees set theirs, with each adapter's apply step (ADR 0013).
 */
class ReviewTreeSummaryTest : BasePlatformTestCase() {

    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null
    private val service get() = ChangesSummaryService.getInstance(project)

    override fun setUp() {
        super.setUp()
        saved = settings.state
        settings.loadState(SmartDiffSettings.SettingsState())
        service.waitForSummaries()
        service.clear()
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

    /** A tree with [changes] and summaries attached through [adapter], shown once the summaries are computed. */
    private fun summarized(changes: List<Change>, adapter: ReviewTreeAdapter = GitLogTreeAdapter()): TestReviewTree {
        val tree = TestReviewTree(project)
        ReviewTreeSummaries(project, tree, adapter, testRootDisposable)
        tree.showChanges(changes)
        settle(tree)
        return tree
    }

    /** Waits for the summaries and the rebuild they ask for. */
    private fun settle(tree: TestReviewTree) {
        service.waitForSummaries()
        Thread.sleep(ChangesSummaryService.REFRESH_DELAY_MILLIS + 100)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        tree.rebuildTree()
    }

    private fun summaryRow(tree: ChangesTree, change: Change): ReviewSummaryNode? =
        tree.root.depthFirstEnumeration().toList().filterIsInstance<ReviewSummaryNode>().firstOrNull { it.change === change }

    fun `test a changed file of the Git log gets its summary line after its name`() {
        // changes-tree/07 R1, R5
        val change = modified("Cart.java")
        val tree = summarized(listOf(change))

        val row = summaryRow(tree, change)
        assertNotNull("the file row has a summary", row)
        assertEquals("the same text as the Changes tree", "1 changed · 1 added", row!!.summary)
        val renderer = ChangesBrowserNodeRenderer(project, BooleanGetter { false }, false)
        row.render(renderer, false, false, false)
        val text = renderer.getCharSequence(false).toString()
        assertTrue(text, text.startsWith("Cart.java"))
        assertTrue(text, text.endsWith("1 changed · 1 added"))
    }

    fun `test the summary is added before the platform restores the tree state`() {
        // changes-tree/07 R1 (spike S11: the model property is set between saving and restoring the state)
        val change = modified("Cart.java")
        val tree = summarized(listOf(change))
        var rowsAtRestore: List<ReviewSummaryNode>? = null

        tree.rebuildTree(object : ChangesTree.TreeStateStrategy<Unit> {
            override fun saveState(tree: ChangesTree) = Unit

            override fun restoreState(tree: ChangesTree, state: Unit, scrollToSelection: Boolean) {
                rowsAtRestore = tree.root.depthFirstEnumeration().toList().filterIsInstance<ReviewSummaryNode>()
            }
        })

        assertEquals("the summary row is in the model when the platform restores the state", listOf(change), rowsAtRestore?.map { it.change })
    }

    fun `test a summarized file expands to its declaration rows`() {
        // changes-tree/07 R6
        val change = modified("Cart.java")
        val tree = summarized(listOf(change))

        val row = summaryRow(tree, change)!!
        val labels = row.children().toList().filterIsInstance<DeclarationNode>().map { it.item.title }
        assertEquals(listOf("class Cart"), labels)
        val nested = (row.firstChild as DeclarationNode).children().toList().filterIsInstance<DeclarationNode>().map { it.item.title }
        assertEquals(listOf("method total()", "method count()"), nested)
        assertFalse("the row starts collapsed", tree.isExpanded(TreePath(row.path)))
    }

    fun `test added and deleted files get a summary`() {
        // changes-tree/07 R7
        val added = Change(null, CountingRevision(ReviewTreeFixtures.NEW, path("New.java"), "2"))
        val deleted = Change(CountingRevision(ReviewTreeFixtures.OLD, path("Old.java"), "1"), null)
        val tree = summarized(listOf(added, deleted))

        assertEquals("3 added", summaryRow(tree, added)?.summary)
        assertEquals("2 removed", summaryRow(tree, deleted)?.summary)
    }

    fun `test a file over the limits shows the reason without declaration rows`() {
        // changes-tree/07 R8
        settings.limits = Limits.DEFAULT.copy(maxChars = 10)
        val change = modified("Cart.java")
        val tree = summarized(listOf(change))

        val row = summaryRow(tree, change)!!
        assertEquals("not analyzed: too large", row.summary)
        assertEquals(0, row.childCount)
    }

    fun `test unsupported and unreadable files keep the platform row`() {
        // changes-tree/07 R9
        val binary = modified("image.png")
        val path = path("Gone.java")
        val unreadable = Change(CountingRevision(null, path, "1"), CountingRevision(ReviewTreeFixtures.NEW, path, "2"))
        val tree = summarized(listOf(binary, unreadable))

        assertNull(summaryRow(tree, binary))
        assertNull(summaryRow(tree, unreadable))
        assertEquals("both files keep a row", 2, tree.fileRows().size)
    }

    fun `test the change type filter applies to review trees`() {
        // changes-tree/07 R13
        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.ADDED)
        val change = modified("Cart.java")
        val tree = summarized(listOf(change))

        val row = summaryRow(tree, change)!!
        assertEquals("1 changed · 1 added · 1 hidden", row.summary)
        val nested = (row.firstChild as DeclarationNode).children().toList().filterIsInstance<DeclarationNode>().map { it.item.title }
        assertEquals(listOf("method total()"), nested)
    }

    fun `test declaration rows are not changes`() {
        // changes-tree/07 R14
        val first = modified("Cart.java")
        val second = modified("Order.java")
        val tree = summarized(listOf(first, second))

        assertEquals(setOf(first, second), VcsTreeModelData.all(tree).userObjects(Change::class.java).toSet())
        assertEquals("the file count is the platform's", 2, tree.root.fileCount)
        val declaration = summaryRow(tree, first)!!.firstChild as DeclarationNode
        tree.selectionPath = treePath(declaration)
        assertEquals("a declaration selects no change", emptyList<Change>(), VcsTreeModelData.selected(tree).userObjects(Change::class.java))
        tree.selectionPath = TreePath(summaryRow(tree, first)!!.path)
        assertEquals(listOf(first), VcsTreeModelData.selected(tree).userObjects(Change::class.java))
    }

    fun `test the first 500 file nodes of a review tree are summarized`() {
        // changes-tree/07 R15
        ReviewTreeFixtures.maskEngines(testRootDisposable, InstantReviewEngine())
        val changes = (1..ChangesSummaryModifier.MAX_FILES + 1).map { modified("File${"%04d".format(it)}.java") }
        val tree = summarized(changes)

        val rows = tree.fileRows()
        assertEquals(changes.size, rows.size)
        assertEquals(ChangesSummaryModifier.MAX_FILES, rows.count { it is ReviewSummaryNode })
        assertFalse("the last file keeps the platform row", rows.last() is ReviewSummaryNode)
    }

    fun `test the settings that hide summaries leave the platform rows`() {
        // changes-tree/07 R12
        settings.showChangesSummary = false
        settings.showChangesDeclarations = false
        val change = modified("Cart.java")
        val tree = summarized(listOf(change))

        assertNull(summaryRow(tree, change))
        val revisions = listOf(change.beforeRevision, change.afterRevision).map { it as CountingRevision }
        assertEquals("nothing is compared, as in the Changes tree", 0, revisions.sumOf { it.reads })
    }

    fun `test pull and merge request nodes are summarized from their change`() {
        // changes-tree/07 R2, R3
        for (adapter in listOf(TestCodeReviewAdapter(GitHubTreeAdapter()), TestCodeReviewAdapter(GitLabTreeAdapter()))) {
            service.clear()
            val tree = TestReviewTree(project)
            val disposable = Disposer.newDisposable()
            try {
                ReviewTreeSummaries(project, tree, adapter, disposable)
                val ref = RefComparisonChange(revision("base"), path("Cart.java"), revision("head"), path("Cart.java"))
                tree.nodes = listOf(path("Cart.java") to { reviewNode(ref) })
                tree.rebuildTree()
                settle(tree)

                val row = tree.root.depthFirstEnumeration().toList().filterIsInstance<ReviewSummaryNode>().single()
                assertEquals(adapter.name, "1 changed · 1 added", row.summary)
                assertSame("the node keeps the request's change", ref, row.userObject)
                assertEquals(listOf(ref), VcsTreeModelData.all(tree).userObjects(RefComparisonChange::class.java))
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

    fun `test the adapters recognise only their trees`() {
        // changes-tree/07 R1 to R3
        val plain = TestReviewTree(project)
        assertFalse(GitLogTreeAdapter().accepts(plain))
        assertFalse("a tree without request changes is not a pull request tree", GitHubTreeAdapter().accepts(plain))
        assertFalse(GitLabTreeAdapter().accepts(plain))

        val ref = RefComparisonChange(revision("base"), path("Cart.java"), revision("head"), path("Cart.java"))
        val reviewTree = TestReviewTree(project).apply {
            nodes = listOf(path("Cart.java") to { reviewNode(ref) })
            rebuildTree()
        }
        assertFalse("outside the tool windows and plugin components", GitHubTreeAdapter().accepts(reviewTree))
        assertFalse(GitLabTreeAdapter().accepts(reviewTree))
        assertFalse("a request tree is not the log's tree", GitLogTreeAdapter().accepts(reviewTree))
    }

    private fun treePath(node: javax.swing.tree.TreeNode): TreePath =
        TreePath(generateSequence(node) { it.parent }.toList().asReversed().toTypedArray())

    private fun reviewNode(change: RefComparisonChange): ChangesBrowserNode<*> = object : ChangesBrowserNode<RefComparisonChange>(change) {
        override fun render(renderer: ChangesBrowserNodeRenderer, selected: Boolean, expanded: Boolean, hasFocus: Boolean) {
            renderer.append(change.filePathAfter?.name ?: "")
        }
    }

    /** A request adapter whose revisions are fixed texts instead of Git revisions, which a light test cannot read. */
    private class TestCodeReviewAdapter(private val real: CodeReviewTreeAdapter) : TestAdapter() {
        override val name: String get() = real.name

        override fun changeOf(tree: ChangesTree, node: ChangesBrowserNode<*>): Change? {
            val change = real.changeOf(tree, node) ?: return null
            val before = change.beforeRevision!!
            val after = change.afterRevision!!
            return Change(
                CountingRevision(ReviewTreeFixtures.OLD, before.file, before.revisionNumber.asString()),
                CountingRevision(ReviewTreeFixtures.NEW, after.file, after.revisionNumber.asString()),
            )
        }
    }

    companion object {
        fun revision(hash: String): ShortVcsRevisionNumber = object : ShortVcsRevisionNumber {
            override fun asString() = hash
            override fun toShortString() = hash
            override fun compareTo(other: com.intellij.openapi.vcs.history.VcsRevisionNumber?) = asString().compareTo(other?.asString() ?: "")
        }
    }
}
