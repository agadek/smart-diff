package io.github.agadek.smartdiff.ui.changes

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.editor.DiffViewerVirtualFile
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.impl.DiffEditorViewer
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.ListSelection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
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
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.ClickListener.SyntheticClickEvent
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

    fun `test a double-click on a summarized file row opens its diff directly`() {
        // changes-tree/02-changes-tree-declarations R17
        VcsApplicationSettings.getInstance().SHOW_DIFF_ON_DOUBLE_CLICK = true
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
        VcsApplicationSettings.getInstance().SHOW_DIFF_ON_DOUBLE_CLICK = true
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

    fun `test a summarized row matches a plain row in Local Changes with diff on`() {
        // changes-tree/02-changes-tree-declarations R17: the platform choice, compared with a plain row
        assertSummaryMatchesPlain("local-diff", TestContext.LOCAL, showDiff = true, showPreview = false)
    }

    fun `test a summarized row matches a plain row in Local Changes with diff off`() {
        // changes-tree/02-changes-tree-declarations R17: the platform choice, compared with a plain row
        assertSummaryMatchesPlain("local-source", TestContext.LOCAL, showDiff = false, showPreview = false)
    }

    fun `test a summarized row matches a plain row in the Commit tool window with preview on`() {
        // changes-tree/02-changes-tree-declarations R17: the editor preview, compared with a plain row
        assertSummaryMatchesPlain("commit-preview", TestContext.COMMIT, showDiff = false, showPreview = true)
    }

    fun `test a summarized row matches a plain row in the Commit tool window with preview off`() {
        // changes-tree/02-changes-tree-declarations R17: the source fallback, compared with a plain row
        assertSummaryMatchesPlain("commit-source", TestContext.COMMIT, showDiff = false, showPreview = false)
    }

    fun `test opening the Commit preview reads the revisions off the EDT and switches changes`() {
        // changes-tree/02-changes-tree-declarations R19: the preview reads each file like the platform's
        VcsApplicationSettings.getInstance().let {
            it.SHOW_DIFF_ON_DOUBLE_CLICK = false
            it.SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK = true
        }
        val cartPath = VcsUtil.getFilePath("/repo/Cart.java", false)
        val orderPath = VcsUtil.getFilePath("/repo/Order.java", false)
        val cartBefore = CheckedRevision(OLD_CART, cartPath)
        val cartAfter = CheckedRevision(NEW_CART, cartPath)
        val orderBefore = CheckedRevision(OLD_ORDER, orderPath)
        val orderAfter = CheckedRevision(NEW_ORDER, orderPath)
        val tree = summarizedTree(Change(cartBefore, cartAfter), Change(orderBefore, orderAfter))
        // The summary already read both revisions; only the preview's reads are checked.
        listOf(cartBefore, cartAfter, orderBefore, orderAfter).forEach { it.read = false }
        val cartRow = TreePath(summaryRow(tree, "Cart.java").path)
        val orderRow = TreePath(summaryRow(tree, "Order.java").path)
        reveal(tree, cartRow)
        reveal(tree, orderRow)

        withToolWindow(tree, ToolWindowId.COMMIT) {
            ChangesTreeSetup.configure(tree, suppressExpansion = true)
            val previewManager = RecordingDiffEditorTabFilesManager()
            val previewDisposable = Disposer.newDisposable(testRootDisposable)
            project.replaceService(DiffEditorTabFilesManager::class.java, previewManager, previewDisposable)
            try {
                // The click runs on the EDT; a revision read there fails in CheckedRevision.
                ApplicationManager.getApplication().invokeAndWait { tree.doubleClick(cartRow) }
                val preview = previewManager.shown ?: error("the preview opens")
                waitForReads("the background load reads the first change", cartBefore, cartAfter)
                val viewer = (preview as DiffViewerVirtualFile).createViewer(project) as DiffRequestProcessor
                PlatformTestUtil.waitWithEventsDispatching(
                    "the preview shows the first change",
                    { viewer.activeRequest?.title == cartPath.path },
                    10,
                )
                assertFalse("the second change is not read yet", orderBefore.read || orderAfter.read)

                // Switching the preview to a second change loads and shows it, without a revision read on the EDT.
                ApplicationManager.getApplication().invokeAndWait { tree.doubleClick(orderRow) }
                waitForReads("switching the preview reads the second change", orderBefore, orderAfter)
                PlatformTestUtil.waitWithEventsDispatching(
                    "the preview switches to the second change",
                    { viewer.activeRequest?.title == orderPath.path },
                    10,
                )
            } finally {
                ChangesTreeSetup.disposePreview(tree)
                Disposer.dispose(previewDisposable)
            }
        }
    }

    fun `test reopening the Commit preview after its tab closed loads it again`() {
        // changes-tree/02-changes-tree-declarations R19: closing the preview tab disposes its viewer
        VcsApplicationSettings.getInstance().let {
            it.SHOW_DIFF_ON_DOUBLE_CLICK = false
            it.SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK = true
        }
        val path = VcsUtil.getFilePath("/repo/Cart.java", false)
        val before = CheckedRevision(OLD_CART, path)
        val after = CheckedRevision(NEW_CART, path)
        val tree = summarizedTree(Change(before, after))
        before.read = false
        after.read = false
        val summaryPath = TreePath(summaryRow(tree).path)
        reveal(tree, summaryPath)

        withToolWindow(tree, ToolWindowId.COMMIT) {
            ChangesTreeSetup.configure(tree, suppressExpansion = true)
            val previewManager = RecordingDiffEditorTabFilesManager()
            val previewDisposable = Disposer.newDisposable(testRootDisposable)
            project.replaceService(DiffEditorTabFilesManager::class.java, previewManager, previewDisposable)
            try {
                ApplicationManager.getApplication().invokeAndWait { tree.doubleClick(summaryPath) }
                val first = previewManager.shown ?: error("the preview opens")
                waitForReads("the preview reads both revisions", before, after)

                // Closing the editor tab disposes the diff viewer; the next preview must build a new one.
                Disposer.dispose((first as DiffViewerVirtualFile).createViewer(project) as Disposable)
                before.read = false
                after.read = false
                ApplicationManager.getApplication().invokeAndWait { tree.doubleClick(summaryPath) }
                waitForReads("the reopened preview reads both revisions", before, after)
                val reopened = (previewManager.shown as DiffViewerVirtualFile).createViewer(project) as DiffRequestProcessor
                PlatformTestUtil.waitWithEventsDispatching(
                    "the reopened preview shows the file",
                    { reopened.activeRequest?.title == path.path },
                    10,
                )
            } finally {
                ChangesTreeSetup.disposePreview(tree)
                Disposer.dispose(previewDisposable)
            }
        }
    }

    private fun waitForReads(message: String, vararg revisions: CheckedRevision) {
        PlatformTestUtil.waitWithEventsDispatching(message, { revisions.all { it.read } }, 10)
    }

    fun `test moving the tree between tool windows uses the new setting`() {
        // changes-tree/02-changes-tree-declarations R17: the context is resolved at click time
        VcsApplicationSettings.getInstance().let {
            it.SHOW_DIFF_ON_DOUBLE_CLICK = true
            it.SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK = false
        }
        val (javaChange, _) = realChange("move-context/Cart.java", OLD_CART, NEW_CART)
        val (textChange, _) = realChange("move-context/notes.txt", "old\n", "new\n")
        val tree = summarizedTree(javaChange, textChange)
        val summaryPath = TreePath(summaryRow(tree).path)
        reveal(tree, summaryPath)

        val manager = ToolWindowManager.getInstance(project)
        val existingLocal = manager.getToolWindow(ToolWindowId.VCS)
        val local = existingLocal ?: manager.registerToolWindow(RegisterToolWindowTask.notClosable(ToolWindowId.VCS, ToolWindowAnchor.LEFT))
        val existingCommit = manager.getToolWindow(ToolWindowId.COMMIT)
        val commit = existingCommit ?: manager.registerToolWindow(RegisterToolWindowTask.notClosable(ToolWindowId.COMMIT, ToolWindowAnchor.LEFT))
        val content = ContentFactory.getInstance().createContent(tree, "Changes", false)
        local.contentManager.addContent(content)
        try {
            ChangesTreeSetup.configure(tree, suppressExpansion = true)
            val shown = recordDiffs()
            try {
                tree.doubleClick(summaryPath)
                assertTrue("Local Changes opens the diff first", shown.chains.isNotEmpty())
            } finally {
                Disposer.dispose(shown)
            }

            local.contentManager.removeContent(content, false)
            commit.contentManager.addContent(content)
            VcsApplicationSettings.getInstance().let {
                it.SHOW_DIFF_ON_DOUBLE_CLICK = false
                it.SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK = true
            }
            val previewManager = RecordingDiffEditorTabFilesManager()
            val previewDisposable = Disposer.newDisposable(testRootDisposable)
            project.replaceService(DiffEditorTabFilesManager::class.java, previewManager, previewDisposable)
            try {
                tree.doubleClick(summaryPath)
                assertNotNull("the moved tree uses the Commit preview setting", previewManager.shown)
            } finally {
                ChangesTreeSetup.disposePreview(tree)
                Disposer.dispose(previewDisposable)
            }
        } finally {
            if (existingLocal == null) manager.unregisterToolWindow(ToolWindowId.VCS) else local.contentManager.removeContent(content, true)
            if (existingCommit == null) manager.unregisterToolWindow(ToolWindowId.COMMIT) else commit.contentManager.removeContent(content, true)
        }
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

    private enum class TestContext { LOCAL, COMMIT }

    private enum class Action { DIFF, PREVIEW, SOURCE }

    private fun assertSummaryMatchesPlain(
        name: String,
        context: TestContext,
        showDiff: Boolean,
        showPreview: Boolean,
    ) {
        VcsApplicationSettings.getInstance().let {
            it.SHOW_DIFF_ON_DOUBLE_CLICK = showDiff
            it.SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK = showPreview
        }
        val (javaChange, javaFile) = realChange("$name/Cart.java", OLD_CART, NEW_CART)
        val (textChange, _) = realChange("$name/notes.txt", "old\n", "new\n")
        val tree = summarizedTree(javaChange, textChange)
        val summary = summaryRow(tree)
        val summaryPath = TreePath(summary.path)
        val plain = VcsTreeModelData.all(tree).iterateNodes()
            .filterIsInstance<ChangesBrowserChangeNode>()
            .first { it !is SummaryChangeNode }
        val plainPath = TreePath(plain.path)
        reveal(tree, summaryPath)
        reveal(tree, plainPath)

        val plainChoice = when {
            context == TestContext.COMMIT && showPreview -> Action.PREVIEW
            context == TestContext.LOCAL && showDiff -> Action.DIFF
            else -> Action.SOURCE
        }
        val plainActions = mutableListOf<Action>()
        tree.doubleClickHandler = Processor { plainActions += plainChoice; true }
        withToolWindow(tree, if (context == TestContext.COMMIT) ToolWindowId.COMMIT else ToolWindowId.VCS) {
            ChangesTreeSetup.configure(tree, suppressExpansion = true)
            val previewManager = RecordingDiffEditorTabFilesManager()
            val previewDisposable = Disposer.newDisposable(testRootDisposable)
            project.replaceService(DiffEditorTabFilesManager::class.java, previewManager, previewDisposable)
            val shown = if (plainChoice == Action.DIFF) recordDiffs() else null
            try {
                tree.doubleClick(summaryPath)
                val observed = when {
                    shown?.chains?.isNotEmpty() == true -> Action.DIFF
                    previewManager.shown != null -> Action.PREVIEW
                    FileEditorManager.getInstance(project).isFileOpen(javaFile) -> Action.SOURCE
                    else -> error("the summarized row did neither diff, preview nor source")
                }
                tree.doubleClick(plainPath)
                assertEquals("the plain row uses one platform choice", listOf(plainChoice), plainActions)
                assertEquals("the summarized row follows the plain row's platform choice", plainChoice, observed)
            } finally {
                ChangesTreeSetup.disposePreview(tree)
                shown?.let(Disposer::dispose)
                Disposer.dispose(previewDisposable)
            }
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

    /** Records the editor-preview tab instead of opening one through the test editor manager. */
    private class RecordingDiffEditorTabFilesManager : DiffEditorTabFilesManager {
        var shown: VirtualFile? = null

        override fun showDiffFile(diffFile: VirtualFile, focusEditor: Boolean): Array<out FileEditor> {
            shown = diffFile
            return emptyArray()
        }

        override fun isDiffOpenedInWindow(file: VirtualFile): Boolean = false
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

        val OLD_ORDER = """
            // order
            class Order {
                int count() {
                    return 1;
                }
            }
        """.trimIndent()

        val NEW_ORDER = """
            // order
            class Order {
                int count() {
                    return 2;
                }

                int size() {
                    return 0;
                }
            }
        """.trimIndent()
    }
}
