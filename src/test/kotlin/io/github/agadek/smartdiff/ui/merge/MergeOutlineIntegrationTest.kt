package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.ThreesideTextDiffViewer
import com.intellij.diff.util.ThreeSide
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
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
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

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
        return MergeOutlineController.of(openViewer(request)) ?: error("no merge outline attached")
    }

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

    private fun viewer(outline: MergeOutlineController): ThreesideTextDiffViewer = outline.viewer

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
