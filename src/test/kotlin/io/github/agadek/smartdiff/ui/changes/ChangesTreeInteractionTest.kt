package io.github.agadek.smartdiff.ui.changes

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.impl.DiffEditorViewer
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.ListSelection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesTreeEditorDiffPreview
import com.intellij.openapi.vcs.changes.LocalChangeListImpl
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangeListRemoteState
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeListNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.NoneChangesGroupingFactory
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.ClickListener.SyntheticClickEvent
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.Processor
import com.intellij.vcsUtil.VcsUtil
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import java.awt.HeadlessException
import java.awt.Point
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.plaf.basic.BasicTreeUI
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
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

    fun `test a double-click on a summarized file row opens its diff directly`() {
        // changes-tree/02-changes-tree-declarations R17
        val tree = summarizedTree(modified("Cart.java"))
        val summary = summaryRow(tree)
        val path = TreePath(summary.path)
        reveal(tree, path)
        tree.doubleClickHandler = Processor { error("the platform handler must not handle a summarized row") }
        ChangesTreeSetup.configure(tree, suppressExpansion = true)

        val press = tree.pressOnly(tree.centerOf(path), clickCount = 2)
        assertTrue("the second press is suppressed", press.isConsumed)
        assertSame("the suppressed press selects the summarized row", summary, tree.selectionPath?.lastPathComponent)
        assertFalse("the suppressed press does not expand the row", tree.isExpanded(path))

        val shown = recordDiffs()
        try {
            tree.doubleClick(path)
        } finally {
            Disposer.dispose(shown)
        }
        assertTrue("the file diff opens directly", shown.chains.isNotEmpty())
        assertFalse("the setup does not expand the row", tree.isExpanded(path))
    }

    fun `test Enter on a summarized file row opens its diff directly`() {
        // changes-tree/02-changes-tree-declarations R18
        val tree = summarizedTree(modified("Cart.java"))
        val summary = summaryRow(tree)
        val path = TreePath(summary.path)
        reveal(tree, path)
        tree.enterKeyHandler = Processor { error("the platform handler must not handle a summarized row") }
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
        tree.selectionPath = path

        val shown = recordDiffs()
        try {
            tree.pressKey(KeyEvent.VK_ENTER)
        } finally {
            Disposer.dispose(shown)
        }
        assertTrue("Enter opens the file diff directly", shown.chains.isNotEmpty())
    }

    fun `test a single-click selection reaches the preview handler`() {
        // changes-tree/02-changes-tree-declarations R19
        val change = modified("Cart.java")
        val tree = summarizedTree(change)
        val summary = summaryRow(tree)
        val path = TreePath(summary.path)
        reveal(tree, path)
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
        val preview = RecordingPreview(tree)
        try {
            tree.press(tree.centerOf(path))

            assertEquals("the preview handler runs once", 1, preview.selections.size)
            assertEquals("the preview sees the file's change", listOf(change), preview.selections.single())
            assertSame("the click selects the summarized file row", summary, tree.selectionPath?.lastPathComponent)
            assertEquals("the preview's selection is the file's change", listOf(change), tree.selectedChanges.toList())
            assertEquals("the included set still has only the file change", listOf(change), VcsTreeModelData.all(tree).userObjects())
        } finally {
            Disposer.dispose(preview)
        }
    }

    fun `test a summarized file row still expands and collapses from the keyboard`() {
        // changes-tree/02-changes-tree-declarations R20
        val tree = summarizedTree(modified("Cart.java"))
        val summary = summaryRow(tree)
        val path = TreePath(summary.path)
        reveal(tree, path)
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
        tree.selectionPath = path

        tree.pressKey(KeyEvent.VK_RIGHT)
        assertTrue("Right expands the file row", tree.isExpanded(path))

        tree.pressKey(KeyEvent.VK_LEFT)
        assertFalse("Left collapses the file row", tree.isExpanded(path))
    }

    fun `test ordinary directory and changelist rows still expand when expansion is suppressed`() {
        // changes-tree/02-changes-tree-declarations R17 regression: only the SMART rows change their double-click
        val tree = groupedTree()
        val directory = tree.root.getChildAt(0) as ChangesBrowserNode<*>
        val changelist = tree.root.getChildAt(1) as ChangesBrowserNode<*>
        ChangesTreeSetup.configure(tree, suppressExpansion = true)

        listOf(directory, changelist).forEach { node ->
            val path = TreePath(node.path)
            reveal(tree, path)
            tree.realDoubleClick(path)
            assertTrue("a double-click expands ${node.javaClass.simpleName}", tree.isExpanded(path))
            tree.realDoubleClick(path)
            assertFalse("a second double-click collapses ${node.javaClass.simpleName}", tree.isExpanded(path))
        }
    }

    fun `test turning summaries off restores ordinary double-click expansion`() {
        // changes-tree/02-changes-tree-declarations R17 regression: turning summaries off resets the tree
        val tree = groupedTree()
        val directory = tree.root.getChildAt(0) as ChangesBrowserNode<*>
        val path = TreePath(directory.path)
        reveal(tree, path)
        ChangesTreeSetup.configure(tree, suppressExpansion = true)

        val manager = ToolWindowManager.getInstance(project)
        val id = listOf(ToolWindowId.VCS, ToolWindowId.COMMIT).firstOrNull { manager.getToolWindow(it) == null }
            ?: error("no free Changes tool window")
        val window = manager.registerToolWindow(RegisterToolWindowTask.notClosable(id, ToolWindowAnchor.LEFT))
        window.contentManager.addContent(ContentFactory.getInstance().createContent(tree, "Changes", false))
        try {
            settings.showChangesSummary = false
            val builder = TreeModelBuilder(project, NoneChangesGroupingFactory).setChanges(emptyList(), null)
            ChangesSummaryModifier(project).modifyTreeModelBuilder(builder)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            assertTrue(
                "turning summaries off restores the platform's expansion",
                EditSourceOnDoubleClickHandler.isExpandPreferable(tree, path),
            )
            tree.realDoubleClick(path)
            assertTrue("a real double-click expands the directory after reset", tree.isExpanded(path))
        } finally {
            manager.unregisterToolWindow(id)
        }
    }

    fun `test a file row expands when its expand control is clicked`() {
        // changes-tree/02-changes-tree-declarations R20
        val tree = summarizedTree(modified("Cart.java"))
        val summary = summaryRow(tree)
        val path = TreePath(summary.path)
        reveal(tree, path)
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
        tree.collapsePath(path)
        assertFalse("the row starts collapsed", tree.isExpanded(path))

        val point = expandControlPoint(tree, path)
        tree.press(point)
        assertTrue("clicking the expand control expands the row", tree.isExpanded(path))

        tree.press(point)
        assertFalse("clicking the expand control again collapses the row", tree.isExpanded(path))
    }

    fun `test a double-click on a declaration's expand control does not open its diff`() {
        // changes-tree/02-changes-tree-declarations R14, R15
        val tree = summarizedTree(modified("Cart.java"))
        val declaration = declarationRows(tree).first { it.childCount > 0 }
        val path = TreePath((declaration as DefaultMutableTreeNode).path)
        reveal(tree, path)
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
        tree.collapsePath(path)
        val point = expandControlPoint(tree, path)

        tree.press(point)
        assertTrue("the expand control expands the declaration row", tree.isExpanded(path))

        val shown = recordDiffs()
        try {
            tree.doubleClickAt(path, point)
        } finally {
            Disposer.dispose(shown)
        }
        assertTrue("the expand control does not open the declaration's diff", shown.chains.isEmpty())
        assertTrue("the row stays expanded", tree.isExpanded(path))
    }

    fun `test configuring twice keeps Enter working for ordinary and declaration rows`() {
        // changes-tree/02-changes-tree-declarations R9, R18 robustness: configure is idempotent
        val tree = summarizedTree(modified("Cart.java"), modified("notes.txt"))
        val declaration = declarationRows(tree).last { it.childCount == 0 }
        val declarationPath = TreePath((declaration as DefaultMutableTreeNode).path)
        val ordinary = VcsTreeModelData.all(tree).iterateNodes()
            .filterIsInstance<ChangesBrowserChangeNode>()
            .first { it !is SummaryChangeNode }
        val ordinaryPath = TreePath(ordinary.path)
        reveal(tree, declarationPath)

        var ordinaryCalls = 0
        tree.enterKeyHandler = Processor { ordinaryCalls++; true }
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
        ChangesTreeSetup.configure(tree, suppressExpansion = true)

        tree.selectionPath = ordinaryPath
        tree.pressKey(KeyEvent.VK_ENTER)
        assertEquals("ordinary Enter still reaches the platform handler once", 1, ordinaryCalls)

        tree.selectionPath = declarationPath
        val shown = recordDiffs()
        try {
            tree.pressKey(KeyEvent.VK_ENTER)
        } finally {
            Disposer.dispose(shown)
        }
        assertTrue("declaration Enter still opens its diff once", shown.chains.size == 1)
    }

    fun `test an unrecognized platform Enter listener is not taken over for declarations`() {
        // changes-tree/02 changes-tree-declarations R9 robustness: fail soft when the platform listener changes
        val tree = summarizedTree(modified("Cart.java"))
        val declaration = declarationRows(tree).last { it.childCount == 0 }
        val path = TreePath((declaration as DefaultMutableTreeNode).path)
        reveal(tree, path)
        tree.keyListeners.filter { it.javaClass.name.endsWith("ChangesTreeHandlers\$MyEnterListener") }
            .forEach(tree::removeKeyListener)

        var platformCalls = 0
        tree.enterKeyHandler = Processor { platformCalls++; true }
        tree.addKeyListener(StandInPlatformEnterListener(tree, tree.enterKeyHandler!!))
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
        tree.selectionPath = path

        val shown = recordDiffs()
        try {
            tree.pressKey(KeyEvent.VK_ENTER)
        } finally {
            Disposer.dispose(shown)
        }
        assertEquals("the stand-in platform handler runs once", 1, platformCalls)
        assertTrue("the plugin does not open the declaration itself", shown.chains.isEmpty())
    }

    fun `test an unrecognized platform Enter listener still opens a non-leaf declaration`() {
        // changes-tree/02 changes-tree-declarations R9 robustness: the platform guard skips non-leaf rows
        val tree = summarizedTree(modified("Cart.java"))
        val declaration = declarationRows(tree).first { it.childCount > 0 }
        val path = TreePath((declaration as DefaultMutableTreeNode).path)
        reveal(tree, path)
        tree.keyListeners.filter { it.javaClass.name.endsWith("ChangesTreeHandlers\$MyEnterListener") }
            .forEach(tree::removeKeyListener)
        tree.enterKeyHandler = Processor { error("the platform listener skips non-leaf rows") }
        tree.addKeyListener(StandInPlatformEnterListener(tree, tree.enterKeyHandler!!))
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
        tree.selectionPath = path

        val shown = recordDiffs()
        try {
            tree.pressKey(KeyEvent.VK_ENTER)
        } finally {
            Disposer.dispose(shown)
        }
        assertTrue("the plugin opens the non-leaf declaration itself", shown.chains.isNotEmpty())
    }

    fun `test Enter on a declaration row opens its diff through the selected path`() {
        // changes-tree/02-changes-tree-declarations R9
        val change = modified("Cart.java")
        val tree = summarizedTree(change)
        val declaration = declarationRows(tree).first { it.childCount > 0 }
        val path = TreePath((declaration as DefaultMutableTreeNode).path)
        reveal(tree, path)

        tree.enterKeyHandler = Processor { error("the platform handler must not handle a helper row") }
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
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
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
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

    private fun groupedTree(): TestChangesListView {
        val root = ChangesBrowserNode.createRoot()
        val directory = ChangesBrowserNode.createFilePath(VcsUtil.getFilePath("/repo/src", true))
        directory.markAsHelperNode()
        directory.add(ChangesBrowserNode.createChange(project, modified("Cart.java")))
        root.add(directory)

        val changelist = ChangesBrowserChangeListNode(
            project,
            LocalChangeListImpl.createEmptyChangeListImpl(project, "Changes", null),
            ChangeListRemoteState(),
        )
        changelist.markAsHelperNode()
        changelist.add(ChangesBrowserNode.createChange(project, modified("Other.java")))
        root.add(changelist)

        val tree = TestChangesListView(project)
        tree.updateTreeModel(DefaultTreeModel(root), ChangesTree.DO_NOTHING)
        tree.setSize(800, 600)
        tree.doLayout()
        return tree
    }

    private fun expandControlPoint(tree: TestChangesListView, path: TreePath): Point {
        val bounds = tree.getPathBounds(path) ?: error("row is not visible: $path")
        val ui = tree.ui as BasicTreeUI
        return Point(bounds.x - ui.rightChildIndent + 1 - 4, bounds.y + bounds.height / 2)
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
            // Use the IDE's tree UI so the expand control and toggle geometry match production.
            setUI(DefaultTreeUI())
        }

        override fun rebuildTree() {}

        fun doubleClick(path: TreePath) {
            selectionPath = path
            doubleClickAt(path, point(path))
        }

        fun doubleClickAt(path: TreePath, point: Point) {
            selectionPath = path
            processMouseEvent(
                SyntheticClickEvent(this, System.currentTimeMillis(), 0, point.x, point.y, 2, false, MouseEvent.BUTTON1),
            )
        }

        fun realDoubleClick(path: TreePath) {
            clearSelection()
            val point = point(path)
            val time = System.currentTimeMillis()
            try {
                mouse(MouseEvent.MOUSE_PRESSED, point, time, 1)
                mouse(MouseEvent.MOUSE_RELEASED, point, time, 1)
                mouse(MouseEvent.MOUSE_PRESSED, point, time + 20, 2)
                mouse(MouseEvent.MOUSE_RELEASED, point, time + 20, 2)
            } catch (e: HeadlessException) {
                // BasicTreeUI cannot toggle a row headless; apply the same platform decision.
                selectionPath = path
                if (EditSourceOnDoubleClickHandler.isExpandPreferable(this, path)) {
                    if (isExpanded(path)) collapsePath(path) else expandPath(path)
                }
            }
        }

        fun press(point: Point) {
            val time = System.currentTimeMillis()
            try {
                mouse(MouseEvent.MOUSE_PRESSED, point, time, 1)
                mouse(MouseEvent.MOUSE_RELEASED, point, time, 1)
            } catch (e: HeadlessException) {
                // BasicTreeUI asks the toolkit for the menu shortcut mask on a press; select what a real press would.
                getPathForLocation(point.x, point.y)?.let { selectionPath = it }
            }
        }

        fun pressOnly(point: Point, clickCount: Int): MouseEvent {
            val event = MouseEvent(
                this, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK, point.x, point.y, clickCount, false, MouseEvent.BUTTON1,
            )
            try {
                processMouseEvent(event)
            } catch (e: HeadlessException) {
                // The platform's click listener asks the toolkit for the screen location after the press was handled.
            }
            return event
        }

        fun centerOf(path: TreePath): Point = point(path)

        fun pressKey(keyCode: Int) {
            processKeyEvent(KeyEvent(this, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED))
        }

        private fun point(path: TreePath): Point {
            val bounds = getPathBounds(path) ?: error("row is not visible: $path")
            return Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
        }

        private fun mouse(type: Int, point: Point, whenTime: Long, clickCount: Int) {
            val modifiers = if (type == MouseEvent.MOUSE_PRESSED) InputEvent.BUTTON1_DOWN_MASK else 0
            processMouseEvent(MouseEvent(this, type, whenTime, modifiers, point.x, point.y, clickCount, false, MouseEvent.BUTTON1))
        }
    }

    /** Mimics the platform's leaf-only Enter listener under a class name the plugin does not recognise. */
    private class StandInPlatformEnterListener(
        private val tree: ChangesListView,
        private val handler: Processor<in KeyEvent>,
    ) : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (e.keyCode != KeyEvent.VK_ENTER || e.modifiersEx != 0) return
            val node = tree.lastSelectedPathComponent ?: return
            if (tree.selectionCount <= 1 && !tree.model.isLeaf(node)) return
            if (handler.process(e)) e.consume()
        }
    }

    /** The platform preview handler, with its selection listener and without opening a diff tab. */
    private class RecordingPreview(private val changesTree: ChangesListView) : ChangesTreeEditorDiffPreview(changesTree) {
        val selections = mutableListOf<List<Change>>()

        override fun hasContent(): Boolean = true
        override fun createViewer(): DiffEditorViewer = error("unexpected")
        override fun collectDiffProducers(selectedOnly: Boolean): ListSelection<out DiffRequestProducer>? = null
        override fun getEditorTabName(processor: DiffEditorViewer?): String? = null

        override fun handleSingleClick() {
            selections += changesTree.selectedChanges.toList()
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
