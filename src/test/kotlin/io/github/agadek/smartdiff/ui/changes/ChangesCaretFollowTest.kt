package io.github.agadek.smartdiff.ui.changes

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.ThreesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.ThreeSide
import com.intellij.diff.DiffContentFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsApplicationSettings
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesViewModifier
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.NoneChangesGroupingFactory
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.vcsUtil.VcsUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import io.github.agadek.smartdiff.ui.ChangeType
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import java.awt.Component
import java.awt.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Following the caret in the Changes tree (changes-tree/05). */
class ChangesCaretFollowTest : BasePlatformTestCase() {

    private val settings get() = SmartDiffSettings.getInstance()
    private val service get() = ChangesSummaryService.getInstance(project)
    private var savedSettings: SmartDiffSettings.SettingsState? = null
    private val followers = mutableListOf<ChangesTreeCaretFollower>()
    private val viewers = mutableListOf<Pair<DiffRequest, FrameDiffTool.DiffViewer>>()
    private var savedShowDiff = false
    private var savedShowPreview = true

    override fun setUp() {
        super.setUp()
        savedSettings = settings.state
        settings.loadState(SmartDiffSettings.SettingsState())
        VcsApplicationSettings.getInstance().let {
            savedShowDiff = it.SHOW_DIFF_ON_DOUBLE_CLICK
            savedShowPreview = it.SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK
        }
        service.waitForSummaries()
        service.clear()
        viewers.clear()
        ExtensionTestUtil.addExtensions(
            DiffExtension.EP_NAME,
            listOf(object : DiffExtension() {
                override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
                    viewers += request to viewer
                }
            }),
            testRootDisposable,
        )
    }

    override fun tearDown() {
        try {
            followers.forEach(Disposer::dispose)
            followers.clear()
            VcsApplicationSettings.getInstance().let {
                it.SHOW_DIFF_ON_DOUBLE_CLICK = savedShowDiff
                it.SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK = savedShowPreview
            }
            savedSettings?.let(settings::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test the innermost changed declaration under the caret is selected and the file expands`() {
        // changes-tree/05-changes-tree-caret-follow R1
        maskEngines(RangeEngine())
        val (editor, file) = configuredFile("Cart.java", NEW_TEXT)
        val tree = summarizedTree(change(file))
        val fileRow = summaryRow(tree, file)
        val follower = follower(tree) { editor.contentComponent }

        follower.followNow(editor, NEW_TEXT.indexOf("return 2"))

        val method = tree.selectionPath?.lastPathComponent as? DeclarationNode
        assertEquals("the changed method inside the class is selected", "method total()  changed", method?.item?.label)
        assertTrue("the file row expands to show it", tree.isExpanded(TreePath(fileRow.path)))
        assertTrue("the selected row is made visible", tree.isVisible(declarationPath(method!!)))

        follower.followNow(editor, NEW_TEXT.indexOf("class Cart") + 2)
        val type = tree.selectionPath?.lastPathComponent as? DeclarationNode
        assertEquals("a caret in the changed class header selects the class, not a method", "class Cart  changed", type?.item?.label)
    }

    fun `test the caret outside every changed declaration selects the file row`() {
        // changes-tree/05-changes-tree-caret-follow R2
        maskEngines(RangeEngine())
        val (editor, file) = configuredFile("Cart.java", NEW_TEXT)
        val tree = summarizedTree(change(file))
        val fileRow = summaryRow(tree, file)
        val follower = follower(tree) { editor.contentComponent }

        follower.followNow(editor, NEW_TEXT.indexOf("// header") + 2)

        assertSame("the file row is selected", fileRow, tree.selectionPath?.lastPathComponent)
        assertFalse("no declaration row is selected", tree.selectionPath?.lastPathComponent is DeclarationNode)
    }

    fun `test a plain editor maps to the new side range`() {
        // changes-tree/05-changes-tree-caret-follow R3
        maskEngines(RangeEngine())
        val (editor, file) = configuredFile("Cart.java", NEW_TEXT)
        val tree = summarizedTree(change(file))
        val follower = follower(tree) { editor.contentComponent }

        follower.followNow(editor, NEW_TEXT.indexOf("void added()"))

        val selected = tree.selectionPath?.lastPathComponent as? DeclarationNode
        assertEquals("the new-side range of the added method is used", "method added()  added", selected?.item?.label)
    }

    fun `test the old and new sides of a two-sided diff map to their own ranges`() {
        // changes-tree/05-changes-tree-caret-follow R3
        maskEngines(RangeEngine())
        val file = myFixture.addFileToProject("DiffCart.java", NEW_TEXT).virtualFile
        val tree = summarizedTree(change(file))
        val request = SimpleDiffRequest(
            "DiffCart.java",
            DiffContentFactory.getInstance().create(project, OLD_TEXT, file),
            DiffContentFactory.getInstance().create(project, NEW_TEXT, file),
            "old",
            "new",
        )
        val viewer = openViewer(request) as TwosideTextDiffViewer
        val follower = follower(tree, viewer = viewer)

        follower.followNow(viewer.editor1, OLD_TEXT.indexOf("void removed()"))
        assertEquals(
            "the old side uses its own range",
            "method removed()  removed",
            (tree.selectionPath?.lastPathComponent as? DeclarationNode)?.item?.label,
        )

        follower.followNow(viewer.editor2, NEW_TEXT.indexOf("void added()"))
        assertEquals(
            "the new side uses its own range",
            "method added()  added",
            (tree.selectionPath?.lastPathComponent as? DeclarationNode)?.item?.label,
        )
    }

    fun `test the caret in a three-sided diff does not move the Changes tree`() {
        // changes-tree/05-changes-tree-caret-follow R3
        maskEngines(RangeEngine())
        val file = myFixture.addFileToProject("MergeCart.java", NEW_TEXT).virtualFile
        val tree = summarizedTree(change(file))
        val fileRow = summaryRow(tree, file)
        tree.selectionPath = TreePath(fileRow.path)
        val request = SimpleDiffRequest(
            "MergeCart.java",
            DiffContentFactory.getInstance().create(project, OLD_TEXT, file),
            DiffContentFactory.getInstance().create(project, NEW_TEXT, file),
            DiffContentFactory.getInstance().create(project, NEW_TEXT, file),
            "left",
            "base",
            "right",
        )
        val viewer = openViewer(request) as ThreesideTextDiffViewer
        val follower = follower(tree, viewer = viewer)

        ThreeSide.values().forEach { side ->
            follower.followNow(viewer.getEditor(side), NEW_TEXT.indexOf("return 2"))
            assertSame("a three-sided diff is out of scope", fileRow, tree.selectionPath?.lastPathComponent)
        }
    }

    fun `test the setting turns following off`() {
        // changes-tree/05-changes-tree-caret-follow R4; settings/01-settings-page R15
        maskEngines(RangeEngine())
        val (editor, file) = configuredFile("Cart.java", NEW_TEXT)
        val tree = summarizedTree(change(file))
        val classRow = declarationRow(tree, "class Cart")
        tree.selectionPath = declarationPath(classRow)
        val follower = follower(tree) { editor.contentComponent }

        settings.followChangesTreeCaret = false
        follower.followNow(editor, NEW_TEXT.indexOf("return 2"))

        assertSame("the tree is left as it is while following is off", classRow, tree.selectionPath?.lastPathComponent)
    }

    fun `test following leaves focus and commit inclusion alone with the platform preview off and on`() {
        // changes-tree/05-changes-tree-caret-follow R5
        maskEngines(RangeEngine())
        val (editor, file) = configuredFile("Cart.java", NEW_TEXT)
        val cartChange = change(file)
        val tree = summarizedTree(cartChange)
        val follower = follower(tree) { editor.contentComponent }
        val included = tree.includedSet.toSet()
        val selectedEditor = FileEditorManager.getInstance(project).selectedEditor
        val appSettings = VcsApplicationSettings.getInstance()

        for (preview in listOf(false, true)) {
            appSettings.SHOW_DIFF_ON_DOUBLE_CLICK = preview
            appSettings.SHOW_EDITOR_PREVIEW_ON_DOUBLE_CLICK = preview
            val disposable = Disposer.newDisposable(testRootDisposable)
            val diffs = RecordingDiffManager()
            ApplicationManager.getApplication().replaceService(DiffManager::class.java, diffs, disposable)
            try {
                follower.followNow(editor, NEW_TEXT.indexOf("return 2"))

                assertSame("the editor keeps focus", editor.contentComponent, editor.contentComponent)
                assertEquals("the included changes do not change", included, tree.includedSet.toSet())
                assertTrue("the follower opens no diff with preview $preview", diffs.chains.isEmpty())
                assertSame("the selected editor does not change", selectedEditor, FileEditorManager.getInstance(project).selectedEditor)
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

    fun `test following leaves the tree alone while the tree has focus`() {
        // changes-tree/05-changes-tree-caret-follow R6
        maskEngines(RangeEngine())
        val (editor, file) = configuredFile("Cart.java", NEW_TEXT)
        val tree = summarizedTree(change(file))
        val fileRow = summaryRow(tree, file)
        tree.selectionPath = TreePath(fileRow.path)
        val follower = follower(tree) { tree }

        follower.followNow(editor, NEW_TEXT.indexOf("return 2"))

        assertSame("the tree keeps its selection while it has focus", fileRow, tree.selectionPath?.lastPathComponent)
    }

    fun `test a tree selection is not overwritten by a late caret event`() {
        // changes-tree/05-changes-tree-caret-follow R6
        maskEngines(RangeEngine())
        val (editor, file) = configuredFile("Cart.java", NEW_TEXT)
        val tree = summarizedTree(change(file))
        val clicked = declarationRow(tree, "class Cart")
        var focus: Component? = editor.contentComponent
        val follower = follower(tree) { focus }

        follower.onCaretMoved(editor, NEW_TEXT.indexOf("return 2"))
        // A click focuses the tree and selects another row before the debounce fires.
        focus = tree
        val clickedPath = declarationPath(clicked)
        tree.selectionPath = clickedPath
        focus = editor.contentComponent
        follower.waitForPendingFollow()

        assertSame("the tree's own selection wins over the late caret", clicked, tree.selectionPath?.lastPathComponent)
        assertSame(clickedPath, tree.selectionPath)
    }

    fun `test caret moves are debounced`() {
        // changes-tree/05-changes-tree-caret-follow R6
        maskEngines(RangeEngine())
        val (editor, file) = configuredFile("Cart.java", NEW_TEXT)
        val tree = summarizedTree(change(file))
        val follower = follower(tree) { editor.contentComponent }

        follower.onCaretMoved(editor, NEW_TEXT.indexOf("return 2"))
        follower.onCaretMoved(editor, NEW_TEXT.indexOf("class Cart"))
        follower.onCaretMoved(editor, NEW_TEXT.indexOf("// header"))
        assertEquals("no follow runs before the debounce timer", 0, follower.followRuns)

        follower.waitForPendingFollow()

        assertEquals("several caret moves produce one follow", 1, follower.followRuns)
        val fileRow = summaryRow(tree, file)
        assertSame("the last caret position is used", fileRow, tree.selectionPath?.lastPathComponent)
    }

    fun `test a stale summary leaves the current row and selects the caret row after the rebuild`() {
        // changes-tree/05-changes-tree-caret-follow R7
        maskEngines(RangeEngine())
        val (editor, file) = configuredFile("StaleCart.java", NEW_TEXT)
        val path = resolvingPath(file)
        val change = Change(
            SimpleContentRevision(OLD_TEXT, path, VcsRevisionNumber.NULL),
            SimpleContentRevision(NEW_TEXT, path, VcsRevisionNumber.NULL),
        )
        val tree = summarizedTree(change)
        val classRow = declarationRow(tree, "class Cart")
        tree.selectionPath = declarationPath(classRow)
        val follower = follower(tree) { editor.contentComponent }
        val edited = NEW_TEXT.replace("return 2;", "return 3;")
        ApplicationManager.getApplication().runWriteAction { editor.document.setText(edited) }

        follower.followNow(editor, edited.indexOf("return 3"))
        assertSame("a stale summary keeps the current row", classRow, tree.selectionPath?.lastPathComponent)

        val rebuilt = Change(
            SimpleContentRevision(OLD_TEXT, path, VcsRevisionNumber.NULL),
            SimpleContentRevision(edited, path, VcsRevisionNumber.NULL),
        )
        model(listOf(rebuilt))
        service.waitForSummaries()
        tree.updateTreeModel(model(listOf(rebuilt)), ChangesTree.DO_NOTHING)
        follower.waitForPendingFollow()

        val selected = tree.selectionPath?.lastPathComponent as? DeclarationNode
        assertEquals("the row under the caret is selected after the rebuild", "method total()  changed", selected?.item?.label)
    }

    fun `test a file without a summary selects only the file row`() {
        // changes-tree/05-changes-tree-caret-follow R8
        maskEngines(RangeEngine())
        val (addedEditor, addedFile) = configuredFile("AddedCart.java", NEW_TEXT)
        val added = Change(null, SimpleContentRevision(NEW_TEXT, VcsUtil.getFilePath(addedFile), "2"))
        val addedTree = buildTree(listOf(added))
        val addedRow = fileRow(addedTree, added)
        val addedFollower = follower(addedTree) { addedEditor.contentComponent }
        addedFollower.followNow(addedEditor, NEW_TEXT.indexOf("return 2"))
        assertSame("an added file gets only its file row", addedRow, addedTree.selectionPath?.lastPathComponent)

        val (deletedEditor, deletedFile) = configuredFile("DeletedCart.java", NEW_TEXT)
        val deleted = Change(SimpleContentRevision(OLD_TEXT, VcsUtil.getFilePath(deletedFile), "1"), null)
        val deletedTree = buildTree(listOf(deleted))
        val deletedRow = fileRow(deletedTree, deleted)
        val deletedFollower = follower(deletedTree) { deletedEditor.contentComponent }
        deletedFollower.followNow(deletedEditor, NEW_TEXT.indexOf("return 2"))
        assertSame("a deleted file gets only its file row", deletedRow, deletedTree.selectionPath?.lastPathComponent)

        val (textEditor, textFile) = configuredFile("notes.txt", "old\n")
        val text = Change(SimpleContentRevision("old\n", VcsUtil.getFilePath(textFile), "1"), SimpleContentRevision("new\n", VcsUtil.getFilePath(textFile), "2"))
        val textTree = buildTree(listOf(text))
        val textRow = fileRow(textTree, text)
        val textFollower = follower(textTree) { textEditor.contentComponent }
        textFollower.followNow(textEditor, 1)
        assertSame("a file without an engine gets only its file row", textRow, textTree.selectionPath?.lastPathComponent)
    }

    fun `test a pending file selects only the file row`() {
        // changes-tree/05-changes-tree-caret-follow R8
        val engine = BlockingEngine(ComparisonResult(ENGINE_ID, "1", Status.ANALYZED))
        maskEngines(engine)
        val (editor, file) = configuredFile("Pending.java", NEW_TEXT)
        val change = change(file)
        val tree = buildTree(listOf(change))
        val row = fileRow(tree, change)
        val follower = follower(tree) { editor.contentComponent }
        assertTrue("the comparison started", engine.started.await(10, TimeUnit.SECONDS))

        follower.followNow(editor, NEW_TEXT.indexOf("return 2"))

        assertSame("a pending summary selects the file row", row, tree.selectionPath?.lastPathComponent)
        engine.release.countDown()
        service.waitForSummaries()
    }

    fun `test a not analyzed file selects only the file row`() {
        // changes-tree/05-changes-tree-caret-follow R8
        maskEngines(RangeEngine { ComparisonResult(ENGINE_ID, "1", Status.TOO_LARGE) })
        val (editor, file) = configuredFile("Large.java", NEW_TEXT)
        val change = change(file)
        val tree = summarizedTree(change)
        val row = summaryRow(tree, file)
        assertEquals("not analyzed: too large", row.summary)
        val follower = follower(tree) { editor.contentComponent }

        follower.followNow(editor, NEW_TEXT.indexOf("return 2"))

        assertSame("a reason with no rows selects the file row", row, tree.selectionPath?.lastPathComponent)
        assertTrue("it has no declaration rows", tree.selectionPath?.lastPathComponent !is DeclarationNode)
    }

    fun `test a file after the 500-file limit selects only its file row`() {
        // changes-tree/05-changes-tree-caret-follow R8
        maskEngines(RangeEngine())
        val files = (1..ChangesSummaryModifier.MAX_FILES + 1).map {
            myFixture.addFileToProject("Limit%04d.java".format(it), NEW_TEXT).virtualFile
        }
        val changes = files.map { change(it) }
        val tree = summarizedTree(*changes.toTypedArray())
        val afterLimit = VcsTreeModelData.all(tree).iterateNodes().filterIsInstance<ChangesBrowserChangeNode>()
            .first { it !is SummaryChangeNode }
        val last = afterLimit.userObject as Change
        val file = files.single { it.path == (last.afterRevision ?: last.beforeRevision)!!.file.path }
        myFixture.openFileInEditor(file)
        val editor = myFixture.editor
        val follower = follower(tree) { editor.contentComponent }

        follower.followNow(editor, NEW_TEXT.indexOf("return 2"))

        assertSame("a file after the limit gets only its file row", afterLimit, tree.selectionPath?.lastPathComponent)
    }

    fun `test following reads no revision content on the UI thread`() {
        // changes-tree/05-changes-tree-caret-follow R9
        maskEngines(RangeEngine())
        val (editor, file) = configuredFile("Checked.java", NEW_TEXT)
        val path = VcsUtil.getFilePath(file)
        val before = CheckedRevision(OLD_TEXT, path)
        val after = CheckedRevision(NEW_TEXT, path)
        val change = Change(before, after)
        val tree = summarizedTree(change)
        before.read = false
        after.read = false
        val follower = follower(tree) { editor.contentComponent }

        ApplicationManager.getApplication().invokeAndWait { follower.followNow(editor, NEW_TEXT.indexOf("return 2")) }

        assertFalse("the old revision content is not read on the EDT", before.read)
        assertFalse("the new revision content is not read on the EDT", after.read)
        assertEquals("method total()  changed", (tree.selectionPath?.lastPathComponent as? DeclarationNode)?.item?.label)
    }

    fun `test the tree filter hides the caret row and selects the nearest shown row above`() {
        // changes-tree/05-changes-tree-caret-follow R10
        maskEngines(RangeEngine())
        val (editor, file) = configuredFile("FilteredCart.java", NEW_TEXT)
        val change = change(file)
        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.CHANGED)
        val tree = summarizedTree(change)
        val follower = follower(tree) { editor.contentComponent }

        follower.followNow(editor, NEW_TEXT.indexOf("return 2"))

        val container = tree.selectionPath?.lastPathComponent as? DeclarationNode
        assertEquals("the shown class container above the hidden method is selected", "class Cart", container?.item?.title)
        assertTrue("the hidden method is not a changed row", container?.item?.tags?.isEmpty() == true)

        settings.hiddenChangesTreeChangeTypes = ChangeType.entries.toSet()
        val allHiddenTree = summarizedTree(change)
        val allHiddenFollower = follower(allHiddenTree) { editor.contentComponent }
        allHiddenFollower.followNow(editor, NEW_TEXT.indexOf("return 2"))
        assertSame(
            "with every declaration hidden the file row is selected",
            summaryRow(allHiddenTree, file),
            allHiddenTree.selectionPath?.lastPathComponent,
        )

        settings.hiddenChangesTreeChangeTypes = emptySet()
        settings.showChangesDeclarations = false
        val fileOnlyTree = summarizedTree(change)
        val fileOnlyRow = summaryRow(fileOnlyTree, file)
        val fileOnlyFollower = follower(fileOnlyTree) { editor.contentComponent }
        fileOnlyFollower.followNow(editor, NEW_TEXT.indexOf("return 2"))
        assertSame("with declarations off the file row is selected", fileOnlyRow, fileOnlyTree.selectionPath?.lastPathComponent)
    }

    fun `test changing only the follow setting neither clears the summary cache nor rebuilds the tree`() {
        // settings/01-settings-page R14
        val engine = RangeEngine()
        maskEngines(engine)
        val (_, file) = configuredFile("SettingsCart.java", NEW_TEXT)
        val change = change(file)
        buildTree(listOf(change))
        service.waitForSummaries()
        val calls = engine.calls.get()
        val rebuilds = AtomicInteger()
        project.messageBus.connect(testRootDisposable).subscribe(ChangesViewModifier.TOPIC, ChangesViewModifier.ChangesViewModifierListener {
            rebuilds.incrementAndGet()
        })
        val listener = ChangesSummarySettingsListener()
        listener.settingsChanged()
        val baseline = rebuilds.get()

        settings.followChangesTreeCaret = false
        listener.settingsChanged()

        assertEquals("the follow setting does not rebuild the tree", baseline, rebuilds.get())
        buildTree(listOf(change))
        service.waitForSummaries()
        assertEquals("the follow setting does not compare the file again", calls, engine.calls.get())
    }

    private fun configuredFile(name: String, text: String): Pair<Editor, VirtualFile> {
        val psi = myFixture.configureByText(name, text)
        return myFixture.editor to psi.virtualFile
    }

    private fun change(file: VirtualFile, oldText: String = OLD_TEXT, newText: String = NEW_TEXT): Change =
        Change(
            SimpleContentRevision(oldText, VcsUtil.getFilePath(file), "1"),
            SimpleContentRevision(newText, VcsUtil.getFilePath(file), "2"),
        )

    /** A path that resolves to [file], so a current-content revision's version follows the document stamp. */
    private fun resolvingPath(file: VirtualFile): FilePath =
        object : FilePath by VcsUtil.getFilePath(file.path, false) {
            override fun getVirtualFile(): VirtualFile = file
        }

    private fun model(changes: List<Change>): DefaultTreeModel {
        val builder = TreeModelBuilder(project, NoneChangesGroupingFactory).setChanges(changes, null)
        ChangesSummaryModifier(project).modifyTreeModelBuilder(builder)
        return builder.build()
    }

    private fun buildTree(changes: List<Change>): TestChangesListView {
        val tree = TestChangesListView(project)
        tree.updateTreeModel(model(changes), ChangesTree.DO_NOTHING)
        tree.setSize(900, 700)
        tree.doLayout()
        return tree
    }

    private fun summarizedTree(vararg changes: Change): TestChangesListView {
        val list = changes.toList()
        buildTree(list)
        service.waitForSummaries()
        return buildTree(list)
    }

    private fun follower(
        tree: ChangesListView,
        viewer: FrameDiffTool.DiffViewer? = null,
        focus: () -> Component? = { null },
    ): ChangesTreeCaretFollower =
        ChangesTreeCaretFollower(project, { tree }, service, focus, { true }) { viewer }.also { followers += it }

    private fun summaryRow(tree: ChangesListView, file: VirtualFile): SummaryChangeNode =
        summaryRow(tree, VcsUtil.getFilePath(file))

    private fun summaryRow(tree: ChangesListView, path: com.intellij.openapi.vcs.FilePath): SummaryChangeNode =
        VcsTreeModelData.all(tree).iterateNodes().filterIsInstance<SummaryChangeNode>().single {
            val change = it.userObject as Change
            change.afterRevision?.file == path || change.beforeRevision?.file == path
        }

    private fun summaryRow(tree: ChangesListView, change: Change): SummaryChangeNode =
        VcsTreeModelData.all(tree).iterateNodes().filterIsInstance<SummaryChangeNode>().single { it.userObject === change }

    private fun fileRow(tree: ChangesListView, change: Change): ChangesBrowserChangeNode =
        VcsTreeModelData.all(tree).iterateNodes().filterIsInstance<ChangesBrowserChangeNode>().single { it.userObject === change }

    private fun declarationRow(tree: ChangesListView, title: String): DeclarationNode =
        declarationRows(tree).single { it.item.title == title }

    private fun declarationRows(tree: ChangesListView): List<DeclarationNode> =
        VcsTreeModelData.all(tree).iterateNodes().filterIsInstance<SummaryChangeNode>()
            .flatMap { it.preorderEnumeration().asSequence().filterIsInstance<DeclarationNode>().toList() }

    private fun declarationPath(node: DeclarationNode): TreePath = TreePath((node as javax.swing.tree.DefaultMutableTreeNode).path)

    private fun openViewer(request: SimpleDiffRequest): FrameDiffTool.DiffViewer {
        val panel = DiffManager.getInstance().createRequestPanel(project, Disposer.newDisposable(testRootDisposable), null)
        panel.setRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("no diff viewer was created", { viewers.any { it.first === request } }, 10)
        return viewers.last { it.first === request }.second
    }

    private fun maskEngines(vararg engines: ComparisonEngine) {
        ExtensionTestUtil.maskExtensions(
            ComparisonEngineFactory.EP_NAME,
            engines.map { engine ->
                object : ComparisonEngineFactory {
                    override fun create(project: Project?): ComparisonEngine = engine
                }
            },
            testRootDisposable,
        )
    }

    private class TestChangesListView(project: Project) : ChangesListView(project, false) {
        init {
            setDragEnabled(false)
            setUI(DefaultTreeUI())
        }

        override fun rebuildTree() {}

        override fun isShowing(): Boolean = true
    }

    private class RangeEngine(
        private val result: (ComparisonRequest) -> ComparisonResult = ::cartResult,
    ) : ComparisonEngine {
        override val id = ENGINE_ID
        override val displayName = "Ranged (test)"
        override val version = "1"
        val calls = AtomicInteger()

        override fun supports(languageId: String) = languageId == "JAVA"

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
            calls.incrementAndGet()
            return result(request)
        }
    }

    private class BlockingEngine(private val result: ComparisonResult) : ComparisonEngine {
        override val id = ENGINE_ID
        override val displayName = "Blocking (test)"
        override val version = "1"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun supports(languageId: String) = languageId == "JAVA"

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            return result
        }
    }

    private class RecordingDiffManager : DiffManager() {
        val chains = mutableListOf<DiffRequestChain>()

        override fun showDiff(project: Project?, request: DiffRequest) {}
        override fun showDiff(project: Project?, request: DiffRequest, hints: com.intellij.diff.DiffDialogHints) {}
        override fun showDiff(project: Project?, requests: DiffRequestChain, hints: com.intellij.diff.DiffDialogHints) {
            chains += requests
        }
        override fun createRequestPanel(project: Project?, parent: Disposable, window: Window?): DiffRequestPanel = error("unexpected")
        override fun showMerge(project: Project?, request: MergeRequest) {}
    }

    private companion object {
        val ENGINE_ID = EngineId("smartdiff.test.caret-follow")

        val OLD_TEXT = """
            // header
            class Cart {
                int total() {
                    return 1;
                }

                void removed() {
                }
            }
        """.trimIndent()

        val NEW_TEXT = """
            // header
            class Cart {
                int total() {
                    return 2;
                }

                void added() {
                }
            }
        """.trimIndent()

        fun cartResult(request: ComparisonRequest): ComparisonResult {
            val old = request.oldText.toString()
            val new = request.newText.toString()
            return ComparisonResult(
                ENGINE_ID,
                "1",
                Status.ANALYZED,
                changes = listOf(
                    ChangeNode(
                        Subject.Declaration(DeclarationKind.CLASS, "Cart", null),
                        classRange(old),
                        classRange(new),
                        Presence.MATCHED,
                        content = Content.CHANGED,
                        children = listOf(
                            ChangeNode(
                                Subject.Declaration(DeclarationKind.METHOD, "total", ""),
                                methodRange(old, "int total()"),
                                methodRange(new, "int total()"),
                                Presence.MATCHED,
                                content = Content.CHANGED,
                            ),
                            ChangeNode(
                                Subject.Declaration(DeclarationKind.METHOD, "removed", ""),
                                methodRange(old, "void removed()"),
                                null,
                                Presence.REMOVED,
                            ),
                            ChangeNode(
                                Subject.Declaration(DeclarationKind.METHOD, "added", ""),
                                null,
                                methodRange(new, "void added()"),
                                Presence.ADDED,
                            ),
                        ),
                    ),
                ),
            )
        }

        fun classRange(text: String) = TextRange(text.indexOf("class Cart"), text.length)

        fun methodRange(text: String, signature: String): TextRange? {
            val start = text.indexOf(signature)
            if (start < 0) return null
            val end = text.indexOf('}', start)
            return if (end < 0) null else TextRange(start, end + 1)
        }
    }
}
