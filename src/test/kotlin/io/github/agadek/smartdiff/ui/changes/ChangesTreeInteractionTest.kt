package io.github.agadek.smartdiff.ui.changes

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.NoneChangesGroupingFactory
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.ClickListener.SyntheticClickEvent
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.Processor
import com.intellij.vcsUtil.VcsUtil
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import java.awt.Point
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** The Changes tree handlers, driven through real mouse and key events rather than direct action calls. */
class ChangesTreeInteractionTest : BasePlatformTestCase() {

    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        saved = settings.state
        settings.loadState(SmartDiffSettings.SettingsState())
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

    fun `test a double-click on a summarized file row reaches the platform handler`() {
        // changes-tree/02-changes-tree-declarations R17
        val tree = summarizedTree(modified("Cart.java"))
        val summary = summaryRow(tree)
        val path = TreePath(summary.path)
        reveal(tree, path)

        assertTrue(
            "without the setup the platform would expand the row",
            EditSourceOnDoubleClickHandler.isExpandPreferable(tree, path),
        )

        val handled = mutableListOf<MouseEvent>()
        tree.doubleClickHandler = Processor { handled += it; true }
        ChangesTreeSetup.install(tree)
        assertFalse(
            "the setup stops the platform expanding the row",
            EditSourceOnDoubleClickHandler.isExpandPreferable(tree, path),
        )
        tree.doubleClick(path)

        assertEquals("the platform's double-click handler runs once", 1, handled.size)
        assertFalse("the setup does not expand the row", tree.isExpanded(path))
    }

    fun `test Enter on a summarized file row reaches the platform handler`() {
        // changes-tree/02-changes-tree-declarations R18
        val tree = summarizedTree(modified("Cart.java"))
        val summary = summaryRow(tree)
        val path = TreePath(summary.path)
        reveal(tree, path)

        val handled = mutableListOf<KeyEvent>()
        tree.enterKeyHandler = Processor { handled += it; true }
        tree.selectionPath = path
        tree.pressKey(KeyEvent.VK_ENTER)
        assertTrue("the platform skips Enter for a non-leaf row without the setup", handled.isEmpty())

        ChangesTreeSetup.install(tree)
        tree.pressKey(KeyEvent.VK_ENTER)

        assertEquals("the platform's Enter handler runs once", 1, handled.size)
    }

    fun `test a single click on a summarized file row selects the file for the diff preview`() {
        // changes-tree/02-changes-tree-declarations R19
        val change = modified("Cart.java")
        val tree = summarizedTree(change)
        val summary = summaryRow(tree)
        val path = TreePath(summary.path)
        reveal(tree, path)

        var doubleClicked = false
        tree.doubleClickHandler = Processor { doubleClicked = true; true }
        var entered = false
        tree.enterKeyHandler = Processor { entered = true; true }
        ChangesTreeSetup.install(tree)

        tree.click(path)

        assertSame("a single click selects the summarized file row", summary, tree.selectionPath?.lastPathComponent)
        assertEquals("the preview's selection is the file's change", listOf(change), tree.selectedChanges.toList())
        assertFalse("a single click is not a double-click", doubleClicked)
        assertFalse("a single click is not Enter", entered)
    }

    fun `test a summarized file row still expands and collapses from the keyboard`() {
        // changes-tree/02-changes-tree-declarations R20
        val tree = summarizedTree(modified("Cart.java"))
        val summary = summaryRow(tree)
        val path = TreePath(summary.path)
        reveal(tree, path)
        ChangesTreeSetup.install(tree)
        tree.selectionPath = path

        tree.pressKey(KeyEvent.VK_RIGHT)
        assertTrue("Right expands the file row", tree.isExpanded(path))

        tree.pressKey(KeyEvent.VK_LEFT)
        assertFalse("Left collapses the file row", tree.isExpanded(path))
    }

    fun `test Enter on a declaration row opens its diff through the selected path`() {
        // changes-tree/02-changes-tree-declarations R9
        val change = modified("Cart.java")
        val tree = summarizedTree(change)
        val declaration = declarationRows(tree).first { it.childCount > 0 }
        val path = TreePath((declaration as DefaultMutableTreeNode).path)
        reveal(tree, path)

        tree.enterKeyHandler = Processor { error("the platform handler must not handle a helper row") }
        ChangesTreeSetup.install(tree)
        tree.selectionPath = path

        val shown = recordDiffs()
        try {
            tree.pressKey(KeyEvent.VK_ENTER)
        } finally {
            Disposer.dispose(shown)
        }
        assertTrue("Enter opens the declaration's diff", shown.chains.isNotEmpty())
    }

    fun `test double-click on a declaration row opens its diff through the selected path`() {
        // changes-tree/02-changes-tree-declarations R14
        val change = modified("Cart.java")
        val tree = summarizedTree(change)
        val declaration = declarationRows(tree).first { it.childCount > 0 }
        val path = TreePath((declaration as DefaultMutableTreeNode).path)
        reveal(tree, path)

        tree.doubleClickHandler = Processor { error("the platform handler must not handle a helper row") }
        tree.collapsePath(path)
        ChangesTreeSetup.install(tree)
        assertFalse("the row starts collapsed", tree.isExpanded(path))

        val shown = recordDiffs()
        try {
            tree.doubleClick(path)
        } finally {
            Disposer.dispose(shown)
        }
        assertTrue("double-click opens the declaration's diff", shown.chains.isNotEmpty())
        assertFalse("double-click on the row does not expand it", tree.isExpanded(path))
    }

    private fun summarizedTree(vararg changes: Change): TestChangesListView {
        buildTree(changes.toList())
        ChangesSummaryService.getInstance(project).waitForSummaries()
        return buildTree(changes.toList())
    }

    private fun buildTree(changes: List<Change>): TestChangesListView {
        val builder = TreeModelBuilder(project, NoneChangesGroupingFactory).setChanges(changes, null)
        ChangesSummaryModifier(project).modifyTreeModelBuilder(builder)
        val tree = TestChangesListView(project)
        tree.updateTreeModel(builder.build(), ChangesTree.DO_NOTHING)
        tree.setSize(800, 600)
        tree.doLayout()
        return tree
    }

    private fun summaryRow(tree: ChangesListView): SummaryChangeNode =
        VcsTreeModelData.all(tree).iterateNodes().filterIsInstance<SummaryChangeNode>().single()

    private fun declarationRows(tree: ChangesListView): List<DeclarationNode> =
        summaryRow(tree).preorderEnumeration().toList().filterIsInstance<DeclarationNode>()

    private fun reveal(tree: TestChangesListView, path: TreePath) {
        generateSequence(path.parentPath) { it.parentPath }.toList().asReversed().forEach(tree::expandPath)
        tree.setSize(800, 600)
        tree.doLayout()
    }

    private fun recordDiffs(): RecordingDiffs {
        val disposable = Disposer.newDisposable(testRootDisposable)
        val manager = RecordingDiffManager()
        ApplicationManager.getApplication().replaceService(DiffManager::class.java, manager, disposable)
        return RecordingDiffs(disposable, manager)
    }

    private fun modified(fileName: String): Change {
        val path = VcsUtil.getFilePath("/repo/$fileName", false)
        return Change(SimpleContentRevision(OLD_CART, path, "1"), SimpleContentRevision(NEW_CART, path, "2"))
    }

    /** A Local Changes tree: unlike [ChangesTree], it expands on a double-click unless the plugin suppresses it. */
    private class TestChangesListView(project: Project) : ChangesListView(project, false) {

        init {
            // DnD would take the press/release events before the click listeners in a test that has no real drag.
            setDragEnabled(false)
            // The IDE's tree UI reads ExpandOnDoubleClick; the macOS default UI in the test does not.
            setUI(DefaultTreeUI())
        }

        override fun rebuildTree() {}

        fun click(path: TreePath) {
            selectionPath = path
            syntheticClick(path, 1)
        }

        fun doubleClick(path: TreePath) {
            selectionPath = path
            syntheticClick(path, 2)
        }

        fun pressKey(keyCode: Int) {
            processKeyEvent(KeyEvent(this, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED))
        }

        private fun point(path: TreePath): Point {
            val bounds = getPathBounds(path) ?: error("row is not visible: $path")
            return Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
        }

        private fun syntheticClick(path: TreePath, clickCount: Int) {
            val point = point(path)
            processMouseEvent(
                SyntheticClickEvent(this, System.currentTimeMillis(), 0, point.x, point.y, clickCount, false, MouseEvent.BUTTON1),
            )
        }
    }

    private class RecordingDiffs(val disposable: Disposable, val manager: RecordingDiffManager) : Disposable {
        val chains get() = manager.chains
        override fun dispose() = Disposer.dispose(disposable)
    }

    /** Records the diffs the platform's diff action shows instead of opening windows. */
    private class RecordingDiffManager : DiffManager() {
        val chains = mutableListOf<DiffRequestChain>()

        override fun showDiff(project: Project?, request: DiffRequest) {}
        override fun showDiff(project: Project?, request: DiffRequest, hints: DiffDialogHints) {}
        override fun showDiff(project: Project?, requests: DiffRequestChain, hints: DiffDialogHints) {
            chains += requests
        }

        override fun createRequestPanel(project: Project?, parent: Disposable, window: Window?): DiffRequestPanel = error("unexpected")
        override fun showMerge(project: Project?, request: MergeRequest) {}
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
