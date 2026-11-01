package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.ThreesideTextDiffViewer
import com.intellij.diff.util.ThreeSide
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.lines.LineEngine
import io.github.agadek.smartdiff.engine.lines.LineEngineFactory
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import io.github.agadek.smartdiff.ui.ChangeType
import io.github.agadek.smartdiff.ui.DiffContents
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import io.github.agadek.smartdiff.ui.ToggleOutlineAction
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import org.junit.Assert.assertNotEquals

/**
 * The merge outline in a three-sided diff (merge outline spec 02 and three-sided diff spec 03): attachment, side names,
 * group and container rows, three-pane selection and the outline toggle.
 */
class MergeOutlineIntegrationTest : BasePlatformTestCase() {

    private val viewers = mutableListOf<Pair<DiffRequest, FrameDiffTool.DiffViewer>>()
    private val settings get() = SmartDiffSettings.getInstance()
    private var savedSettings: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        savedSettings = settings.state
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
            savedSettings?.let(settings::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test the three-sided diff gets grouped rows with side names`() {
        // merge/02-merge-outline R1, R4, R8; merge/03-three-sided-diffs R1, R7
        val outline = open().awaitShown()

        assertEquals("1 changed on both sides · 1 in feature only", (outline.state as MergeOutlineState.Shown).summary)
        assertEquals(listOf("Changed on both sides", "Changed in feature only"), rows(outline).filter { !it.startsWith("  ") })
        assertTrue(rows(outline).joinToString("\n"), rows(outline).any { it.trim() == "method total(int)  main: changed  feature: changed" })
    }

    fun `test selecting a row frames all three panes`() {
        // merge/02-merge-outline R16; merge/03-three-sided-diffs R5
        val outline = open().awaitShown()
        select(outline, "method total(int)  main: changed  feature: changed")

        assertHighlightedAt(viewer(outline).getEditor(ThreeSide.LEFT), LEFT.indexOf("int total"))
        assertHighlightedAt(viewer(outline).getEditor(ThreeSide.BASE), BASE.indexOf("int total"))
        assertHighlightedAt(viewer(outline).getEditor(ThreeSide.RIGHT), RIGHT.indexOf("int total"))
        assertEquals("", outline.panel.absence.text)
    }

    fun `test the base and left panes keep their own texts`() {
        // merge/03-three-sided-diffs R1, R2
        val base = "// base comment\n$BASE"
        val outline = open(base = base, left = LEFT, right = base).awaitShown()
        val shown = outline.state as MergeOutlineState.Shown

        assertTrue(shown.summary, shown.summary.contains("in main only"))
        assertFalse(shown.summary, shown.summary.contains("both sides"))
        select(outline, rows(outline).first { it.contains("method total(int)") }.trim())
        assertHighlightedAt(viewer(outline).getEditor(ThreeSide.LEFT), LEFT.indexOf("int total"))
        assertHighlightedAt(viewer(outline).getEditor(ThreeSide.BASE), base.indexOf("int total"))
    }

    fun `test the outline toggle hides and restores the merge outline`() {
        // merge/02-merge-outline R18, R19
        val outline = open().awaitShown()
        val viewer = viewer(outline)
        val action = ActionManager.getInstance().getAction("SmartDiff.ToggleOutline") as ToggleOutlineAction
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getSimpleContext(com.intellij.diff.tools.util.DiffDataKeys.DIFF_VIEWER, viewer))

        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        assertTrue(action.isSelected(event))

        action.setSelected(event, false)
        assertFalse(UIUtil.isAncestor(viewer.component, outline.panel))
        assertEquals(MergeOutlineState.Hidden, outline.state)

        action.setSelected(event, true)
        assertTrue(UIUtil.isAncestor(viewer.component, outline.panel))
        outline.awaitShown()
    }

    fun `test the container layout and the merge filter rebuild the rows`() {
        // merge/02-merge-outline R2, R3, R13, R14, R15
        val outline = open().awaitShown()

        outline.setLayout(MergeOutlineLayout.CONTAINER_TREE)
        assertTrue(rows(outline).contains("class OrderService"))
        assertTrue(rows(outline).contains("  method total(int)  main: changed  feature: changed"))

        outline.setHidden(ChangeType.CHANGED, true)
        assertEquals(emptyList<String>(), rows(outline))
        assertEquals("All changes are hidden by the filter", outline.panel.tree.emptyText.text)

        outline.setHidden(ChangeType.CHANGED, false)
        assertTrue(rows(outline).contains("class OrderService"))

        // The layout is a setting, so a new viewer keeps it; the filter no longer hides the row.
        val next = open().awaitShown()
        assertEquals(MergeOutlineLayout.CONTAINER_TREE, (next.state as MergeOutlineState.Shown).layout)
        assertTrue(rows(next).contains("class OrderService"))
    }

    fun `test an added row says which panes are absent`() {
        // merge/02-merge-outline R17
        val outline = open(right = RIGHT_ADDED).awaitShown()
        val row = rows(outline).first { it.contains("method extra()") }.trim()
        select(outline, row)

        assertTrue(outline.panel.absence.text, outline.panel.absence.text.contains("left"))
        assertTrue(outline.panel.absence.text, outline.panel.absence.text.contains("base"))
    }

    fun `test one unanalyzed side keeps the other side and does not say only`() {
        // merge/02-merge-outline R22
        val engine = LeftFailsEngine()
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(factory(engine)), testRootDisposable)
        val outline = open().awaitShown()

        val shown = outline.state as MergeOutlineState.Shown
        assertTrue(shown.banner.orEmpty(), shown.banner.orEmpty().contains("main was not analyzed"))
        assertTrue(shown.items.isNotEmpty())
        assertTrue(shown.summary, !shown.summary.contains("only"))
    }

    fun `test the engine dropdown and refresh work in the merge outline`() {
        // merge/02-merge-outline R18
        ExtensionTestUtil.addExtensions(ComparisonEngineFactory.EP_NAME, listOf(LineEngineFactory()), testRootDisposable)
        val outline = open().awaitShown()

        assertTrue("the dropdown is shown with two engines", outline.panel.engines.isVisible)
        outline.refresh()
        outline.awaitShown()
        assertTrue(outline.panel.engines.isVisible)
    }

    fun `test the merge outline attaches for Kotlin and Scala`() {
        // merge/02-merge-outline R23
        for ((fileName, base, right) in listOf(
            Triple("Greeter.kt", KOTLIN_BASE, KOTLIN_RIGHT),
            Triple("Greeter.scala", SCALA2_BASE, SCALA2_RIGHT),
            Triple("Greeter.scala", SCALA3_BASE, SCALA3_RIGHT),
        )) {
            val outline = open(fileName = fileName, base = base, left = base, right = right).awaitShown()
            val shown = outline.state as MergeOutlineState.Shown
            assertTrue("$fileName: ${shown.summary}", shown.summary.contains("in feature only"))
            assertTrue("$fileName: ${shown.items}", shown.items.isNotEmpty())
        }
    }

    fun `test a three-sided diff without an engine gets no outline`() {
        // merge/03-three-sided-diffs R1, R4
        val request = SimpleDiffRequest(
            "notes.txt",
            DiffContents.vcsRevision(project, "one\n", "/repo/notes.txt"),
            DiffContents.vcsRevision(project, "base\n", "/repo/notes.txt"),
            DiffContents.vcsRevision(project, "two\n", "/repo/notes.txt"),
            "left",
            "Base",
            "right",
        )

        assertNull(MergeOutlineController.of(openViewer(request)))
    }

    fun `test a comparison over the time limit fails on the EDT`() {
        // merge/02-merge-outline R20
        val engine = SlowEngine()
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(factory(engine)), testRootDisposable)
        settings.limits = Limits.DEFAULT.copy(timeoutMillis = 100)
        val outline = open()

        PlatformTestUtil.waitWithEventsDispatching(
            { "the outline is ${outline.state}" },
            { outline.state is MergeOutlineState.Failed },
            20,
        )
        assertTrue((outline.state as MergeOutlineState.Failed).reason.contains("timed out"))
        assertTrue("the engine was canceled", engine.canceled)
    }

    fun `test selecting a row pauses and then resumes synchronized scrolling`() {
        // merge/03-three-sided-diffs R6
        val outline = open().awaitShown()
        val viewer = viewer(outline)
        val sync = syncSupport(viewer) ?: error("the viewer has no synchronized scrolling")
        val duringSelection = mutableListOf<Boolean>()
        val listener = com.intellij.openapi.editor.event.VisibleAreaListener {
            duringSelection += duringSyncScroll(sync)
        }
        viewer.getEditor(ThreeSide.LEFT).scrollingModel.addVisibleAreaListener(listener)
        try {
            selectFirstChange(outline)
        } finally {
            viewer.getEditor(ThreeSide.LEFT).scrollingModel.removeVisibleAreaListener(listener)
        }

        assertTrue("the selection did not scroll a pane", duringSelection.isNotEmpty())
        assertTrue("a pane scrolled with synchronized scrolling active", duringSelection.all { it })
        assertFalse("the next user scroll does not sync the panes", duringSyncScroll(sync))
    }

    fun `test the outline never claims a merge is safe and never edits a pane`() {
        // merge/02-merge-outline R24
        val outline = open().awaitShown()
        val viewer = viewer(outline)
        val before = ThreeSide.values().map { viewer.getEditor(it).document.text }
        val shown = outline.state as MergeOutlineState.Shown
        val shownText = (listOf(shown.summary, shown.banner.orEmpty()) + rows(outline)).joinToString("\n")
        assertFalse(shownText, shownText.contains("safe", ignoreCase = true))
        assertFalse(shownText, shownText.contains("resolved", ignoreCase = true))

        assertNotNull(selectFirstChange(outline))
        val event = KeyEvent(outline.panel.tree, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n')
        outline.panel.tree.keyListeners.forEach { it.keyPressed(event) }

        assertEquals(before, ThreeSide.values().map { viewer.getEditor(it).document.text })
    }

    fun `test selecting a row on an unanalyzed side says so instead of not present`() {
        // merge/02-merge-outline R26
        val engine = LeftFailsEngine()
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(factory(engine)), testRootDisposable)
        val outline = open().awaitShown()

        selectFirstChange(outline)

        val note = outline.panel.absence.text
        assertTrue(note, note.contains("main was not analyzed"))
        assertFalse(note, note.contains("Not present"))
    }

    fun `test the changed on both sides filter entry is offered only in the container tree`() {
        // merge/02-merge-outline R33
        val outline = open().awaitShown()
        val action = filterAction(outline.panel, "Changed on both sides")
        val event = TestActionEvent.createTestEvent(action)

        action.update(event)
        assertFalse("the entry is offered while grouped by side", event.presentation.isEnabledAndVisible)

        outline.setLayout(MergeOutlineLayout.CONTAINER_TREE)
        action.update(event)
        assertTrue("the entry is missing from the container tree", event.presentation.isEnabledAndVisible)
    }

    fun `test the native three-sided diff keeps its three panes`() {
        // merge/03-three-sided-diffs R3
        val outline = open().awaitShown()
        val viewer = viewer(outline)

        assertInstanceOf(viewer, ThreesideTextDiffViewer::class.java)
        assertEquals(listOf(LEFT, BASE, RIGHT), ThreeSide.values().map { viewer.getEditor(it).document.text })
    }

    fun `test a three-sided diff never shows merged without conflict`() {
        // merge/03-three-sided-diffs R8
        val outline = open().awaitShown()

        fun assertNoMergedNote() {
            val shown = outline.state as MergeOutlineState.Shown
            assertTrue(notes(shown.items).none { it.contains("merged without conflict") })
        }
        assertNoMergedNote()

        outline.setLayout(MergeOutlineLayout.CONTAINER_TREE)
        assertNoMergedNote()
    }

    fun `test an edit to a pane compares again after typing stops`() {
        // merge/03-three-sided-diffs R9
        val contents = editableContents()
        val outline = openRequest(threeSidedRequest(contents)).awaitShown()
        val before = outline.state as MergeOutlineState.Shown

        WriteCommandAction.runWriteCommandAction(project) {
            val document = contents[0].document
            document.insertString(document.text.lastIndexOf("}"), "    void extra() {}\n")
        }
        assertSame("the outline compared again before typing stopped", before, outline.state)

        PlatformTestUtil.waitWithEventsDispatching(
            { "the outline is ${outline.state}" },
            { outline.state.let { it is MergeOutlineState.Shown && it.joined !== before.joined } },
            20,
        )
        val after = outline.state as MergeOutlineState.Shown
        assertNotEquals(before.summary, after.summary)
        assertTrue(after.summary, after.summary.contains("in main only"))
    }

    fun `test a result computed before an edit is never shown`() {
        // merge/03-three-sided-diffs R10
        val engine = GatedEngine()
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(factory(engine)), testRootDisposable)
        val contents = editableContents()
        val outline = openRequest(threeSidedRequest(contents))

        assertTrue("the first comparisons did not start", engine.started.await(10, TimeUnit.SECONDS))
        WriteCommandAction.runWriteCommandAction(project) {
            val document = contents[0].document
            document.insertString(document.text.lastIndexOf("}"), "    void extra() {}\n")
        }
        engine.release.countDown()

        PlatformTestUtil.waitWithEventsDispatching(
            { "the outline is ${outline.state}" },
            { outline.state !is MergeOutlineState.Loading },
            20,
        )
        val shown = outline.state as? MergeOutlineState.Shown
        assertNotNull("the outline did not show a result: ${outline.state}", shown)
        assertFalse(
            "a comparison from before the edit was shown",
            shown!!.joined.left.engineVersion.startsWith("stale") || shown.joined.right.engineVersion.startsWith("stale"),
        )
    }

    private fun open(
        fileName: String = "OrderService.java",
        base: String = BASE,
        left: String = LEFT,
        right: String = RIGHT,
    ): MergeOutlineController {
        val request = SimpleDiffRequest(
            fileName,
            DiffContents.vcsRevision(project, left, "/repo/$fileName"),
            DiffContents.vcsRevision(project, base, "/repo/$fileName"),
            DiffContents.vcsRevision(project, right, "/repo/$fileName"),
            "main",
            "Base",
            "feature",
        )
        return openRequest(request)
    }

    private fun openRequest(request: SimpleDiffRequest): MergeOutlineController =
        MergeOutlineController.of(openViewer(request)) ?: error("no merge outline attached")

    private fun editableContents() = listOf(LEFT, BASE, RIGHT).map {
        DiffContentFactory.getInstance().createEditable(project, it, JavaFileType.INSTANCE)
    }

    private fun threeSidedRequest(contents: List<DocumentContent>) = SimpleDiffRequest(
        "OrderService.java", contents[0], contents[1], contents[2], "main", "Base", "feature",
    )

    private fun openViewer(request: SimpleDiffRequest): FrameDiffTool.DiffViewer {
        val panel = DiffManager.getInstance().createRequestPanel(project, Disposer.newDisposable(testRootDisposable), null)
        panel.setRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("no diff viewer was created", { viewers.any { it.first === request } }, 10)
        return viewers.last { it.first === request }.second
    }

    private fun MergeOutlineController.awaitShown(): MergeOutlineController {
        PlatformTestUtil.waitWithEventsDispatching(
            { "the outline is ${this.state}" },
            { this.state is MergeOutlineState.Shown },
            20,
        )
        return this
    }

    private fun viewer(outline: MergeOutlineController): ThreesideTextDiffViewer =
        viewers.map { it.second }.filterIsInstance<ThreesideTextDiffViewer>().last { MergeOutlineController.of(it) === outline }

    private fun select(outline: MergeOutlineController, label: String) {
        val root = outline.panel.tree.model.root as DefaultMutableTreeNode
        val node = root.depthFirstEnumeration().toList()
            .map { it as DefaultMutableTreeNode }
            .single { (it.userObject as? MergeOutlineItem)?.label == label }
        outline.panel.tree.selectionPath = TreePath(node.path)
    }

    private fun rows(outline: MergeOutlineController): List<String> {
        val rows = mutableListOf<String>()
        fun visit(node: DefaultMutableTreeNode, depth: Int) {
            for (child in node.children()) {
                child as DefaultMutableTreeNode
                rows += "  ".repeat(depth) + (child.userObject as MergeOutlineItem).label
                visit(child, depth + 1)
            }
        }
        visit(outline.panel.tree.model.root as DefaultMutableTreeNode, 0)
        return rows
    }

    private fun selectFirstChange(outline: MergeOutlineController): MergeOutlineItem {
        val root = outline.panel.tree.model.root as DefaultMutableTreeNode
        val node = root.depthFirstEnumeration().toList()
            .map { it as DefaultMutableTreeNode }
            .first { (it.userObject as? MergeOutlineItem)?.isGroup == false }
        outline.panel.tree.selectionPath = TreePath(node.path)
        return node.userObject as MergeOutlineItem
    }

    private fun notes(items: List<MergeOutlineItem>): List<String> =
        items.flatMap { listOfNotNull(it.note) + notes(it.children) }

    private fun filterAction(panel: MergeOutlinePanel, name: String): AnAction {
        val toolbar = findToolbar(panel) ?: error("no merge outline toolbar")
        fun find(group: ActionGroup): AnAction? = group.getChildren(null).firstNotNullOfOrNull { child ->
            if (child.templatePresentation.text == name) child else (child as? ActionGroup)?.let(::find)
        }
        return find(toolbar.actionGroup) ?: error("no $name filter entry")
    }

    private fun findToolbar(component: Component): ActionToolbar? {
        if (component is ActionToolbarImpl) return component
        if (component is Container) {
            for (child in component.components) findToolbar(child)?.let { return it }
        }
        return null
    }

    private fun syncSupport(viewer: ThreesideTextDiffViewer): Any? {
        val field = ThreesideTextDiffViewer::class.java.getDeclaredField("mySyncScrollSupport")
        field.isAccessible = true
        return field.get(viewer)
    }

    private fun duringSyncScroll(sync: Any): Boolean =
        sync.javaClass.getMethod("isDuringSyncScroll").invoke(sync) as Boolean

    private fun assertHighlightedAt(editor: Editor, offset: Int) {
        assertTrue("offset $offset not found", offset >= 0)
        val starts = editor.markupModel.allHighlighters.map { it.startOffset }
        assertTrue("no highlighter starts at $offset; highlighters start at $starts", offset in starts)
    }

    private fun factory(engine: ComparisonEngine) = object : ComparisonEngineFactory {
        override fun create(project: Project?): ComparisonEngine = engine
    }

    /** Fails the left comparison and delegates the right one to the line engine, for the one-side status case. */
    private class LeftFailsEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.merge.leftFails")
        override val displayName = "Left fails (test)"
        override val version = "1"

        override fun supports(languageId: String) = true

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult =
            if (request.newText.toString() == LEFT) {
                ComparisonResult.failed(this, "left parse failed")
            } else {
                LineEngine().compare(request, cancellation)
            }
    }

    /** Blocks the first two comparisons, so a test can edit a pane while the result is still being computed. */
    private class GatedEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.merge.gated")
        override val displayName = "Gated merge (test)"
        override val version = "1"

        private val calls = AtomicInteger()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)

        override fun supports(languageId: String) = true

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
            val call = calls.incrementAndGet()
            if (call <= 2) {
                started.countDown()
                release.await()
                return LineEngine().compare(request, cancellation).copy(engineVersion = "stale-$call")
            }
            cancellation.checkCanceled()
            return LineEngine().compare(request, cancellation)
        }
    }

    /** Never finishes; it checks for cancellation until it is canceled. */
    private class SlowEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.merge.slow")
        override val displayName = "Slow merge (test)"
        override val version = "1"

        @Volatile
        var canceled = false

        override fun supports(languageId: String) = true

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
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

    private companion object {
        const val BASE = "class OrderService {\n    int total(int a) {\n        return a;\n    }\n\n    boolean validate(int a) {\n        return a > 0;\n    }\n}\n"
        const val LEFT = "class OrderService {\n    int total(int a) {\n        return a + 1;\n    }\n\n    boolean validate(int a) {\n        return a > 0;\n    }\n}\n"
        const val RIGHT = "class OrderService {\n    int total(int a) {\n        return a * 2;\n    }\n\n    boolean validate(int a) {\n        return a >= 0;\n    }\n}\n"
        const val RIGHT_ADDED = "class OrderService {\n    int total(int a) {\n        return a * 2;\n    }\n\n    boolean validate(int a) {\n        return a >= 0;\n    }\n\n    void extra() {}\n}\n"
        const val KOTLIN_BASE = "class Greeter {\n    fun hello() = \"hi\"\n}\n"
        const val KOTLIN_RIGHT = "class Greeter {\n    fun hello() = \"hello\"\n}\n"
        const val SCALA2_BASE = "class Greeter {\n  def hello = \"hi\"\n}\n"
        const val SCALA2_RIGHT = "class Greeter {\n  def hello = \"hello\"\n}\n"
        const val SCALA3_BASE = "class Greeter:\n  def hello = \"hi\"\n"
        const val SCALA3_RIGHT = "class Greeter:\n  def hello = \"hello\"\n"
    }
}
