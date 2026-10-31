package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.DiffEditorTitleCustomizer
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeContext
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.merge.MergeTool
import com.intellij.diff.merge.TextMergeRequest
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.lines.LineEngineFactory
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import io.github.agadek.smartdiff.merge.MergeGroup
import io.github.agadek.smartdiff.ui.DiffContents
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import io.github.agadek.smartdiff.ui.ToggleOutlineAction
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** The merge outline in the platform merge window (merge window spec 04). */
class MergeWindowIntegrationTest : BasePlatformTestCase() {

    private val settings get() = SmartDiffSettings.getInstance()
    private var savedSettings: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        savedSettings = settings.state
    }

    override fun tearDown() {
        try {
            savedSettings?.let(settings::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test a Java merge window gets the outline`() {
        // merge/04-merge-window R1
        val (viewer, outline) = open()
        try {
            assertTrue(outline.state is MergeOutlineState.Shown)
            assertTrue((outline.state as MergeOutlineState.Shown).items.isNotEmpty())
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test the platform's own merge viewer stays the window's viewer`() {
        // merge/04-merge-window R2
        val (viewer, _) = open()
        try {
            assertEquals("com.intellij.diff.merge.TextMergeViewer", viewer.javaClass.name)
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test a merge window where the outline cannot attach shows as usual`() {
        // merge/04-merge-window R3
        ExtensionTestUtil.maskExtensions(MergeTool.EP_NAME, listOf(MergeOutlineTool(), PlainMergeTool()), testRootDisposable)
        val output = EditorFactory.getInstance().createDocument(CONFLICTED)
        val request = request(output, titles = listOf("main", "Base", "feature"))
        val context = mergeContext()
        val tool = DiffManagerEx.getInstance().mergeTools.first { it.canShow(context, request) }

        val viewer = tool.createComponent(context, request)
        try {
            assertInstanceOf(viewer, PlainMergeTool.PlainViewer::class.java)
            assertNull(MergeOutlineController.of(viewer))
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test the outline toggle is in the merge window toolbar`() {
        // merge/04-merge-window R4
        val action = ActionManager.getInstance().getAction("SmartDiff.ToggleOutline") as ToggleOutlineAction
        val group = ActionManager.getInstance().getAction("Diff.Conflicts.Additional.Actions") as DefaultActionGroup
        assertTrue(group.getChildren(null).any { it === action })

        val (viewer, outline) = open()
        try {
            val event = TestActionEvent.createTestEvent(
                action,
                SimpleDataContext.getSimpleContext(DiffDataKeys.MERGE_VIEWER, viewer),
            )
            action.update(event)
            assertTrue(event.presentation.isEnabledAndVisible)
            assertTrue(action.isSelected(event))

            action.setSelected(event, false)
            assertFalse(com.intellij.util.ui.UIUtil.isAncestor(viewer.component, outline.panel))

            action.setSelected(event, true)
            outline.awaitReady()
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test a Git side is named after the branch above its pane`() {
        // merge/04-merge-window R5
        val output = EditorFactory.getInstance().createDocument(CONFLICTED)
        val request = request(
            output,
            titles = listOf("Your version", "Base", "Changes from server (revision 1a2b3c4d)"),
        )
        DiffUtil.addTitleCustomizers(
            request,
            listOf(
                DiffEditorTitleCustomizer { JLabel("<html>Changes from branch <b>feature/rates</b></html>") },
                DiffEditorTitleCustomizer { JLabel("Base") },
                DiffEditorTitleCustomizer { JLabel("<html>Changes from branch <b>main</b>, revision 1a2b3c4d</html>") },
            ),
        )
        val (viewer, outline) = open(request)
        try {
            val shown = outline.state as MergeOutlineState.Shown
            assertEquals("feature/rates", shown.leftName.display)
            assertEquals("main", shown.rightName.display)
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test a side without a branch title uses the content title`() {
        // merge/04-merge-window R6
        val (viewer, outline) = open(titles = listOf("Working copy", "Base", "Shelf"))
        try {
            val shown = outline.state as MergeOutlineState.Shown
            assertEquals("Working copy", shown.leftName.display)
            assertEquals("Shelf", shown.rightName.display)
            assertFalse(shown.leftName.display.contains("ours", ignoreCase = true))
            assertFalse(shown.rightName.display.contains("theirs", ignoreCase = true))
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test editing the result does not change the outline`() {
        // merge/04-merge-window R7
        val (viewer, outline, output) = openWithOutput()
        try {
            val before = outline.state as MergeOutlineState.Shown
            WriteCommandAction.runWriteCommandAction(project) { output.insertString(0, "// an edit\n") }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val after = outline.state as MergeOutlineState.Shown
            assertEquals(before.summary, after.summary)
            assertEquals(before.items, after.items)
            assertEquals(before.joined, after.joined)
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test selecting a row frames the declaration in the result pane`() {
        // merge/04-merge-window R8
        val (viewer, outline, output) = openWithOutput()
        try {
            select(outline, "method total(int)")
            assertHighlightedAt(resultEditor(output), BASE.indexOf("int total"))
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test the frame follows edits to the result`() {
        // merge/04-merge-window R9
        val (viewer, outline, output) = openWithOutput()
        try {
            val inserted = "// a note\n"
            WriteCommandAction.runWriteCommandAction(project) { output.insertString(0, inserted) }
            select(outline, "method total(int)")
            assertHighlightedAt(resultEditor(output), BASE.indexOf("int total") + inserted.length)
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test a removed declaration says not tracked`() {
        // merge/04-merge-window R10
        val (viewer, outline, output) = openWithOutput()
        try {
            select(outline, "method total(int)")
            val start = BASE.indexOf("    int total")
            val end = BASE.indexOf("\n    int calc")
            WriteCommandAction.runWriteCommandAction(project) { output.deleteString(start, end) }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("not tracked", outline.panel.absence.text)
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test a result that differs from the base shows not tracked inside changed lines`() {
        // merge/04-merge-window R11
        val base = EditorFactory.getInstance().createDocument(BASE)
        val result = EditorFactory.getInstance().createDocument(BASE.replace("return a;", "return a + 1;"))
        val pane = MergeResultPane(project, base, base, base, result)
        try {
            val total = range(BASE.indexOf("    int total"), BASE.indexOf("\n    int calc"))
            val calc = range(BASE.indexOf("    int calc"), BASE.indexOf("\n    boolean validate"))
            pane.prepare(listOf(total, calc))
            PlatformTestUtil.waitWithEventsDispatching({ "the result is not mapped" }, { pane.isMapped }, 60)

            assertEquals("not tracked", pane.note(MergeOutlineItem(MergeOutlineItem.Kind.CHANGE, "total", baseRange = total)))
            assertEquals("", pane.note(MergeOutlineItem(MergeOutlineItem.Kind.CHANGE, "calc", baseRange = calc)))
        } finally {
            pane.dispose()
        }
    }

    fun `test selecting a row frames the left, base, right and result panes`() {
        // merge/04-merge-window R8; merge/02-merge-outline R16
        val output = EditorFactory.getInstance().createDocument(CONFLICTED)
        val request = request(output, titles = listOf("feature/rates", "Base", "main"))
        val (viewer, outline) = open(request)
        try {
            select(outline, "method total(int)")
            assertHighlightedAt(resultEditor(output), BASE.indexOf("int total"))
            assertHighlightedAt(sideEditor(request, 0), LEFT.indexOf("int total"))
            assertHighlightedAt(sideEditor(request, 2), RIGHT.indexOf("int total"))
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test an added row names the panes where it is absent`() {
        // merge/02-merge-outline R17
        val output = EditorFactory.getInstance().createDocument(CONFLICTED)
        val request = request(output, titles = listOf("feature/rates", "Base", "main"), right = RIGHT_ADDED)
        val (viewer, outline) = open(request)
        try {
            select(outline, "method extra()")
            val note = outline.panel.absence.text
            assertTrue(note, note.contains("Not present in the left pane"))
            assertTrue(note, note.contains("not tracked"))
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test accepting a side over a declaration keeps it tracked`() {
        // merge/04-merge-window R9, R10
        val (viewer, outline, output) = openWithOutput()
        try {
            select(outline, "method total(int)")
            val start = BASE.indexOf("    int total")
            val end = BASE.indexOf("\n    int calc")
            val accepted = LEFT.substring(LEFT.indexOf("    int total"), LEFT.indexOf("\n    int calc"))
            WriteCommandAction.runWriteCommandAction(project) { output.replaceString(start, end, accepted) }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertEquals("", outline.panel.absence.text)
            select(outline, "method total(int)")
            assertHighlightedAt(resultEditor(output), BASE.indexOf("int total"))
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test the merge window keeps the engine dropdown`() {
        // merge/02-merge-outline R18
        ExtensionTestUtil.addExtensions(ComparisonEngineFactory.EP_NAME, listOf(LineEngineFactory()), testRootDisposable)
        val (viewer, outline) = open()
        try {
            assertTrue("the engine dropdown is shown", outline.panel.engines.isVisible)
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test Git merge and cherry-pick titles name the branch without ours or theirs`() {
        // merge/04-merge-window R5; merge/02-merge-outline R9
        val cases = listOf(
            listOf("Local Changes", "Base", "Changes from <b>main</b>") to listOf("Local Changes", "main"),
            listOf("Local Changes", "Base", "Changes from cherry-pick 1a2b3c4d") to listOf("Local Changes", "cherry-pick 1a2b3c4d"),
        )
        for ((customizers, expected) in cases) {
            val output = EditorFactory.getInstance().createDocument(CONFLICTED)
            val request = request(output, titles = listOf("Your version", "Base", "Changes from server (revision 1a2b3c4d)"))
            DiffUtil.addTitleCustomizers(request, customizers.map { text -> DiffEditorTitleCustomizer { JLabel(text) } })
            val (viewer, outline) = open(request)
            try {
                val shown = outline.state as MergeOutlineState.Shown
                assertEquals(expected, listOf(shown.leftName.display, shown.rightName.display))
                assertFalse(shown.leftName.display.contains("ours", ignoreCase = true))
                assertFalse(shown.rightName.display.contains("theirs", ignoreCase = true))
            } finally {
                Disposer.dispose(viewer)
            }
        }
    }

    fun `test a late viewer rediff remaps an early mapping`() {
        // merge/04-merge-window R7, R11; plan §6.3
        val left = EditorFactory.getInstance().createDocument(LEFT)
        val base = EditorFactory.getInstance().createDocument(BASE)
        val right = EditorFactory.getInstance().createDocument(RIGHT)
        val result = EditorFactory.getInstance().createDocument(CONFLICTED)
        val totalRange = range(BASE.indexOf("    int total"), BASE.indexOf("\n    int calc"))
        val item = MergeOutlineItem(MergeOutlineItem.Kind.CHANGE, "total", baseRange = totalRange)
        val pane = MergeResultPane(project, left, base, right, result)
        try {
            pane.prepare(listOf(totalRange))
            PlatformTestUtil.waitWithEventsDispatching({ "the result is not mapped" }, { pane.isMapped }, 60)
            assertEquals("not tracked", pane.note(item))

            // The viewer's first comparison replaces the conflict markers with the base after the outline mapped.
            WriteCommandAction.runWriteCommandAction(project) { result.setText(BASE) }
            PlatformTestUtil.waitWithEventsDispatching({ "the result was not remapped" }, { pane.note(item).isEmpty() }, 60)
        } finally {
            pane.dispose()
        }
    }

    fun `test a changed row without a conflict is marked and ordered after conflicts`() {
        // merge/04-merge-window R12, R13
        val (viewer, outline) = open()
        try {
            val rows = (outline.state as MergeOutlineState.Shown).items
                .first { it.group == MergeGroup.CHANGED_ON_BOTH_SIDES }
                .children
            assertEquals(listOf("method total(int)", "method calc(int)"), rows.map { it.title })
            assertEquals(null, rows[0].note)
            assertEquals("merged without conflict", rows[1].note)
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test Enter moves the caret into the result pane`() {
        // merge/04-merge-window R15
        val (viewer, outline, output) = openWithOutput()
        try {
            select(outline, "method total(int)")
            val event = KeyEvent(outline.panel.tree, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n')
            outline.panel.tree.keyListeners.forEach { it.keyPressed(event) }
            assertEquals(BASE.indexOf("int total"), resultEditor(output).caretModel.offset)
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test the outline never edits the result pane`() {
        // merge/04-merge-window R16
        val (viewer, outline, output) = openWithOutput()
        try {
            val before = output.text
            select(outline, "method total(int)")
            val event = KeyEvent(outline.panel.tree, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n')
            outline.panel.tree.keyListeners.forEach { it.keyPressed(event) }
            assertEquals(before, output.text)
        } finally {
            Disposer.dispose(viewer)
        }
    }

    @Suppress("DEPRECATION")
    fun `test closing the merge window releases the outline`() {
        // merge/04-merge-window R17
        val (viewer, outline) = open()
        val result = outline.selection as MergeResultPane
        Disposer.dispose(viewer)

        assertEquals(MergeOutlineState.Hidden, outline.state)
        assertTrue(result.isReleased)
        assertTrue("the result pane is disposed with the viewer", Disposer.isDisposed(result))
        assertNull(MergeOutlineController.of(viewer))
    }

    private fun open(
        request: TextMergeRequest? = null,
        titles: List<String> = listOf("feature/rates", "Base", "main"),
    ): Pair<MergeTool.MergeViewer, MergeOutlineController> {
        val (viewer, outline) = openWithOutput(request, titles)
        return viewer to outline
    }

    private fun openWithOutput(
        request: TextMergeRequest? = null,
        titles: List<String> = listOf("feature/rates", "Base", "main"),
    ): Triple<MergeTool.MergeViewer, MergeOutlineController, Document> {
        val output = request?.outputContent?.document ?: EditorFactory.getInstance().createDocument(CONFLICTED)
        val mergeRequest = request ?: request(output, titles = titles)
        val context = mergeContext()
        val tool = DiffManagerEx.getInstance().mergeTools.first { it.canShow(context, mergeRequest) }
        assertInstanceOf(tool, MergeOutlineTool::class.java)
        val viewer = tool.createComponent(context, mergeRequest)
        viewer.init()
        val outline = MergeOutlineController.of(viewer) ?: error("no merge outline in the merge window")
        outline.awaitReady()
        return Triple(viewer, outline, output)
    }

    private fun MergeOutlineController.awaitReady() {
        PlatformTestUtil.waitWithEventsDispatching(
            { "the outline is $state" },
            { state is MergeOutlineState.Shown },
            60,
        )
        val result = selection as? MergeResultPane ?: return
        PlatformTestUtil.waitWithEventsDispatching(
            { "the result is not mapped" },
            { result.isMapped },
            60,
        )
    }

    private fun request(
        output: Document,
        titles: List<String>,
        left: String = LEFT,
        base: String = BASE,
        right: String = RIGHT,
    ): TextMergeRequest =
        DiffRequestFactory.getInstance()
            .createMergeRequest(project, JavaFileType.INSTANCE, output, listOf(left, base, right), "Merge", titles, null) as TextMergeRequest

    private fun mergeContext(): MergeContext = object : MergeContext() {
        override fun getProject(): Project = this@MergeWindowIntegrationTest.project
        override fun isFocusedInWindow(): Boolean = false
        override fun requestFocusInWindow() {}
        override fun finishMerge(result: MergeResult) {}
    }

    private fun select(outline: MergeOutlineController, title: String) {
        val root = outline.panel.tree.model.root as DefaultMutableTreeNode
        val node = root.depthFirstEnumeration().toList()
            .map { it as DefaultMutableTreeNode }
            .first { (it.userObject as? MergeOutlineItem)?.title == title }
        outline.panel.tree.selectionPath = TreePath(node.path)
    }

    private fun resultEditor(output: Document): Editor =
        EditorFactory.getInstance().getEditors(output, project).firstOrNull() ?: error("no result editor")

    private fun sideEditor(request: TextMergeRequest, index: Int): Editor =
        EditorFactory.getInstance().getEditors(request.contents[index].document, project).firstOrNull()
            ?: error("no editor for side $index")

    private fun assertHighlightedAt(editor: Editor, offset: Int) {
        assertTrue("offset $offset not found", offset >= 0)
        val starts = editor.markupModel.allHighlighters.map { it.startOffset }
        assertTrue("no highlighter starts at $offset; highlighters start at $starts", offset in starts)
    }

    private fun range(start: Int, end: Int) = io.github.agadek.smartdiff.engine.api.TextRange(start, end)

    /** A merge tool whose viewer has a component the outline cannot decorate, for the native-fallback case. */
    private class PlainMergeTool : MergeTool {
        override fun canShow(context: MergeContext, request: com.intellij.diff.merge.MergeRequest): Boolean =
            request is TextMergeRequest

        override fun createComponent(context: MergeContext, request: com.intellij.diff.merge.MergeRequest): MergeTool.MergeViewer =
            PlainViewer()

        class PlainViewer : MergeTool.MergeViewer {
            private val panel = JPanel()
            override fun getComponent(): JComponent = panel
            override fun getPreferredFocusedComponent(): JComponent = panel
            override fun getResolveAction(result: MergeResult): javax.swing.Action? = null
            override fun init(): MergeTool.ToolbarComponents = MergeTool.ToolbarComponents()
            override fun dispose() {}
        }
    }

    private companion object {
        const val BASE = "class OrderService {\n    int total(int a) {\n        return a;\n    }\n\n    int calc(int a) {\n        int x = a;\n        int p1 = 0;\n        int p2 = 0;\n        int p3 = 0;\n        int y = 1;\n        return x + y;\n    }\n\n    boolean validate(int a) {\n        return a > 0;\n    }\n}\n"
        const val LEFT = "class OrderService {\n    int total(int a) {\n        return a + 1;\n    }\n\n    int calc(int a) {\n        int x = a + 1;\n        int p1 = 0;\n        int p2 = 0;\n        int p3 = 0;\n        int y = 1;\n        return x + y;\n    }\n\n    boolean validate(int a) {\n        return a > 0;\n    }\n}\n"
        const val RIGHT = "class OrderService {\n    int total(int a) {\n        return a * 2;\n    }\n\n    int calc(int a) {\n        int x = a;\n        int p1 = 0;\n        int p2 = 0;\n        int p3 = 0;\n        int y = 2;\n        return x + y;\n    }\n\n    boolean validate(int a) {\n        return a >= 0;\n    }\n\n    void extra() {}\n}\n"
        const val RIGHT_ADDED = RIGHT
        const val CONFLICTED = "class OrderService {\n<<<<<<< HEAD\n=======\n>>>>>>> 1a2b3c4d\n}\n"
    }
}
