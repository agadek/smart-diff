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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsApplicationSettings
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
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.ClickListener.SyntheticClickEvent
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.Processor
import com.intellij.openapi.vfs.VirtualFile
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
    private var savedShowDiff = false
    private var savedShowPreview = true

    override fun setUp() {
        super.setUp()
        saved = settings.state
        settings.loadState(SmartDiffSettings.SettingsState())
        VcsApplicationSettings.getInstance().let {
            savedShowDiff = it.SHOW_DIFF_ON_DOUBLE_CLICK
            savedShowPreview = it.SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK
        }
        ChangesSummaryService.getInstance(project).run {
            waitForSummaries()
            clear()
        }
    }

    override fun tearDown() {
        try {
            VcsApplicationSettings.getInstance().let {
                it.SHOW_DIFF_ON_DOUBLE_CLICK = savedShowDiff
                it.SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK = savedShowPreview
            }
            saved?.let(settings::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test the setup reaches the Local Changes and Commit trees`() {
        // changes-tree/02-changes-tree-declarations R17: both tool windows get the same handlers
        settings.showChangesSummary = true
        settings.showChangesDeclarations = true
        val change = modified("Cart.java")
        val local = summarizedTree(change)
        val commit = summarizedTree(change)
        val localPlatform = RecordingPlatform(local) { true }
        val commitPlatform = RecordingPlatform(commit) { true }
        local.doubleClickHandler = localPlatform
        commit.doubleClickHandler = commitPlatform
        local.enterKeyHandler = Processor { localPlatform.enter() }
        commit.enterKeyHandler = Processor { commitPlatform.enter() }

        withToolWindow(local, ToolWindowId.VCS) {
            withToolWindow(commit, ToolWindowId.COMMIT) {
                ChangesTreeSetup.installIn(project)
                local.doubleClick(TreePath(summaryRow(local).path))
                commit.doubleClick(TreePath(summaryRow(commit).path))
            }
        }

        assertEquals("the Local Changes tree reached its platform handler", listOf(1), localPlatform.clickCounts)
        assertEquals("the Commit tree reached its platform handler", listOf(1), commitPlatform.clickCounts)
    }

    fun `test a double-click on a summarized row matches a plain row in Local Changes with diff on`() {
        // changes-tree/02-changes-tree-declarations R17
        assertSummaryMatchesPlain(TestContext.LOCAL, showDiff = true, showPreview = false, Input.DOUBLE_CLICK)
    }

    fun `test a double-click on a summarized row matches a plain row in Local Changes with diff off`() {
        // changes-tree/02-changes-tree-declarations R17
        assertSummaryMatchesPlain(TestContext.LOCAL, showDiff = false, showPreview = false, Input.DOUBLE_CLICK)
    }

    fun `test a double-click on a summarized row matches a plain row in the Commit tool window with preview on`() {
        // changes-tree/02-changes-tree-declarations R17
        assertSummaryMatchesPlain(TestContext.COMMIT, showDiff = false, showPreview = true, Input.DOUBLE_CLICK)
    }

    fun `test a double-click on a summarized row matches a plain row in the Commit tool window with preview off`() {
        // changes-tree/02-changes-tree-declarations R17
        assertSummaryMatchesPlain(TestContext.COMMIT, showDiff = false, showPreview = false, Input.DOUBLE_CLICK)
    }

    fun `test Enter on a summarized row matches a plain row in Local Changes with diff on`() {
        // changes-tree/02-changes-tree-declarations R18
        assertSummaryMatchesPlain(TestContext.LOCAL, showDiff = true, showPreview = false, Input.ENTER)
    }

    fun `test Enter on a summarized row matches a plain row in Local Changes with diff off`() {
        // changes-tree/02-changes-tree-declarations R18
        assertSummaryMatchesPlain(TestContext.LOCAL, showDiff = false, showPreview = false, Input.ENTER)
    }

    fun `test Enter on a summarized row matches a plain row in the Commit tool window with preview on`() {
        // changes-tree/02-changes-tree-declarations R18
        assertSummaryMatchesPlain(TestContext.COMMIT, showDiff = false, showPreview = true, Input.ENTER)
    }

    fun `test Enter on a summarized row matches a plain row in the Commit tool window with preview off`() {
        // changes-tree/02-changes-tree-declarations R18
        assertSummaryMatchesPlain(TestContext.COMMIT, showDiff = false, showPreview = false, Input.ENTER)
    }

    fun `test a summarized row falls back to the source when the platform handler declines`() {
        // changes-tree/02-changes-tree-declarations R18: the platform's source choice
        VcsApplicationSettings.getInstance().SHOW_DIFF_ON_DOUBLE_CLICK = true
        val (change, file) = realChange("fallback/Cart.java", OLD_CART, NEW_CART)
        val tree = summarizedTree(change)
        val summaryPath = TreePath(summaryRow(tree).path)
        reveal(tree, summaryPath)
        tree.doubleClickHandler = Processor { false }
        tree.enterKeyHandler = Processor { false }
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
        tree.selectionPath = summaryPath

        val shown = recordDiffs()
        try {
            tree.pressKey(KeyEvent.VK_ENTER)
        } finally {
            Disposer.dispose(shown)
        }
        assertTrue("the file is opened as the platform would open it", FileEditorManager.getInstance(project).isFileOpen(file))
        assertTrue("no separate diff window is opened", shown.chains.isEmpty())
    }

    fun `test a single click on a summarized row matches a plain row in the preview handler`() {
        // changes-tree/02-changes-tree-declarations R19
        val javaChange = modified("Cart.java")
        val textChange = modified("notes.txt")
        val tree = summarizedTree(javaChange, textChange)
        val summary = summaryRow(tree)
        val summaryPath = TreePath(summary.path)
        val plain = plainRow(tree)
        val plainPath = TreePath(plain.path)
        reveal(tree, summaryPath)
        reveal(tree, plainPath)
        val preview = RecordingPreview(tree)
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
        try {
            tree.press(tree.centerOf(summaryPath))

            assertEquals("the preview handler runs once", 1, preview.selections.size)
            assertEquals("the preview sees the summarized file's change", listOf(javaChange), preview.selections.single())
            assertSame("the click selects the summarized file row", summary, tree.selectionPath?.lastPathComponent)

            tree.press(tree.centerOf(plainPath))

            assertEquals("the preview handler runs again", 2, preview.selections.size)
            assertEquals("the preview sees the plain file's change", listOf(textChange), preview.selections.last())
            assertSame("the click selects the plain file row", plain, tree.selectionPath?.lastPathComponent)
            assertEquals(
                "the included set has both file changes only",
                setOf(javaChange, textChange),
                VcsTreeModelData.all(tree).userObjects().toSet(),
            )
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
        val ordinary = plainRow(tree)
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

    private enum class TestContext { LOCAL, COMMIT }

    private enum class Input { DOUBLE_CLICK, ENTER }

    private enum class Action { PREVIEW, SOURCE }

    private fun assertSummaryMatchesPlain(
        context: TestContext,
        showDiff: Boolean,
        showPreview: Boolean,
        input: Input,
    ) {
        VcsApplicationSettings.getInstance().let {
            it.SHOW_DIFF_ON_DOUBLE_CLICK = showDiff
            it.SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK = showPreview
        }
        val (javaChange, javaFile) = realChange("${context.name.lowercase()}-${input.name.lowercase()}/Cart.java", OLD_CART, NEW_CART)
        val (textChange, textFile) = realChange("${context.name.lowercase()}-${input.name.lowercase()}/notes.txt", "old\n", "new\n")
        val tree = summarizedTree(javaChange, textChange)
        val summaryPath = TreePath(summaryRow(tree).path)
        val plain = plainRow(tree)
        val plainPath = TreePath(plain.path)
        reveal(tree, summaryPath)
        reveal(tree, plainPath)

        val expected = when {
            context == TestContext.COMMIT && showPreview -> Action.PREVIEW
            context == TestContext.LOCAL && showDiff -> Action.PREVIEW
            else -> Action.SOURCE
        }
        val platform = RecordingPlatform(tree) { context == TestContext.COMMIT && showPreview || context == TestContext.LOCAL && showDiff }
        tree.doubleClickHandler = platform
        tree.enterKeyHandler = Processor { platform.enter() }
        ChangesTreeSetup.configure(tree, suppressExpansion = true)
        try {
            activate(tree, summaryPath, input)
            assertEquals("the summarized row follows the platform choice", listOf(expected), platform.actions)
            if (expected == Action.SOURCE) {
                assertTrue("the summarized row opens the file", FileEditorManager.getInstance(project).isFileOpen(javaFile))
            }

            platform.actions.clear()
            activate(tree, plainPath, input)
            assertEquals("the plain row uses the same platform choice", listOf(expected), platform.actions)
            if (expected == Action.SOURCE) {
                assertTrue("the plain row opens the file", FileEditorManager.getInstance(project).isFileOpen(textFile))
            }
        } finally {
            platform.actions.clear()
        }
    }

    private fun activate(tree: TestChangesListView, path: TreePath, input: Input) {
        tree.selectionPath = path
        when (input) {
            Input.DOUBLE_CLICK -> tree.doubleClick(path)
            Input.ENTER -> tree.pressKey(KeyEvent.VK_ENTER)
        }
    }

    private fun <T> withToolWindow(tree: TestChangesListView, id: String, block: () -> T): T {
        val manager = ToolWindowManager.getInstance(project)
        val existing = manager.getToolWindow(id)
        val window = existing ?: manager.registerToolWindow(RegisterToolWindowTask.notClosable(id, ToolWindowAnchor.LEFT))
        val content = ContentFactory.getInstance().createContent(tree, "Changes", false)
        window.contentManager.addContent(content)
        try {
            return block()
        } finally {
            if (existing == null) manager.unregisterToolWindow(id) else window.contentManager.removeContent(content, true)
        }
    }

    private fun realChange(fileName: String, oldText: String, newText: String): Pair<Change, VirtualFile> {
        val file = myFixture.addFileToProject(fileName, newText).virtualFile
        return Change(SimpleContentRevision(oldText, path(file), "1"), SimpleContentRevision(newText, path(file), "2")) to file
    }

    private fun path(file: VirtualFile): FilePath =
        object : FilePath by VcsUtil.getFilePath(file.path, false) {
            override fun getVirtualFile(): VirtualFile = file
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

    private fun summaryRow(tree: ChangesListView, fileName: String? = null): SummaryChangeNode =
        VcsTreeModelData.all(tree).iterateNodes().filterIsInstance<SummaryChangeNode>().single { node ->
            fileName == null || (node.userObject as? Change)?.let { (it.afterRevision ?: it.beforeRevision)?.file?.name } == fileName
        }

    private fun plainRow(tree: ChangesListView): ChangesBrowserChangeNode =
        VcsTreeModelData.all(tree).iterateNodes()
            .filterIsInstance<ChangesBrowserChangeNode>()
            .first { it !is SummaryChangeNode }

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

    /**
     * The platform's double-click and Enter choice: the editor preview when the tool window's setting is on, the
     * source otherwise. It records the click counts and the choice, so a summarized row can be compared with a plain
     * one without opening a real diff tab.
     */
    private class RecordingPlatform(
        private val tree: TestChangesListView,
        private val previewEnabled: () -> Boolean,
    ) : Processor<MouseEvent> {

        val clickCounts = mutableListOf<Int>()
        val actions = mutableListOf<Action>()

        override fun process(e: MouseEvent): Boolean {
            clickCounts += e.clickCount
            return activate(requestFocus = true)
        }

        fun enter(): Boolean = activate(requestFocus = false)

        private fun activate(requestFocus: Boolean): Boolean {
            if (previewEnabled()) {
                actions += Action.PREVIEW
                return true
            }
            val change = tree.selectedChanges.firstOrNull() ?: return false
            val file = (change.afterRevision ?: change.beforeRevision)?.file?.virtualFile ?: return false
            OpenFileDescriptor(tree.project, file).navigate(requestFocus)
            actions += Action.SOURCE
            return true
        }
    }

    /** Mimics the platform's leaf-only Enter listener under a class name the plugin does not recognise. */
    private class StandInPlatformEnterListener(
        private val tree: ChangesListView,
        private val handler: Processor<in KeyEvent>,
    ) : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (e.keyCode != KeyEvent.VK_ENTER || e.modifiersEx != 0) return
            val node = tree.selectionPath?.lastPathComponent ?: return
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
