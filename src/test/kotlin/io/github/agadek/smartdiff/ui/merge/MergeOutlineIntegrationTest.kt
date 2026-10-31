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
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
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

    private fun open(): MergeOutlineController {
        val request = SimpleDiffRequest(
            "OrderService.java",
            DiffContents.vcsRevision(project, LEFT, "/repo/OrderService.java"),
            DiffContents.vcsRevision(project, BASE, "/repo/OrderService.java"),
            DiffContents.vcsRevision(project, RIGHT, "/repo/OrderService.java"),
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

    private companion object {
        const val BASE = "class OrderService {\n    int total(int a) {\n        return a;\n    }\n\n    boolean validate(int a) {\n        return a > 0;\n    }\n}\n"
        const val LEFT = "class OrderService {\n    int total(int a) {\n        return a + 1;\n    }\n\n    boolean validate(int a) {\n        return a > 0;\n    }\n}\n"
        const val RIGHT = "class OrderService {\n    int total(int a) {\n        return a * 2;\n    }\n\n    boolean validate(int a) {\n        return a >= 0;\n    }\n}\n"
    }
}
