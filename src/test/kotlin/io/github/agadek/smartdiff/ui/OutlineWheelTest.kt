package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.MouseWheelEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class OutlineWheelTest : BasePlatformTestCase() {

    private val created = mutableListOf<Pair<DiffRequest, FrameDiffTool.DiffViewer>>()

    override fun setUp() {
        super.setUp()
        val extension = object : com.intellij.diff.DiffExtension() {
            override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: com.intellij.diff.DiffContext, request: DiffRequest) {
                created += request to viewer
            }
        }
        com.intellij.testFramework.ExtensionTestUtil.addExtensions(com.intellij.diff.DiffExtension.EP_NAME, listOf(extension), testRootDisposable)
    }

    fun `test a wheel notch down selects the next row and shows it in the editors`() {
        // outline/08 R1, R2
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        select(outline, "method hello()  changed")

        wheel(outline, 1.0)

        assertEquals("method bye()  added", outline.panel.selectedItem()?.label)
        assertTrue(
            "the new row is shown in the editor",
            outline.navigator.shown.any { it.document === shown.viewer.editor2.document && it.startOffset == JAVA_NEW.indexOf("void bye()") },
        )
    }

    fun `test a wheel notch up selects the previous row`() {
        // outline/08 R1, R2
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        select(outline, "method bye()  added")

        wheel(outline, -1.0)

        assertEquals("method hello()  changed", outline.panel.selectedItem()?.label)
        assertTrue(outline.navigator.shown.any { it.document === shown.viewer.editor2.document && it.startOffset == JAVA_NEW.indexOf("String hello()") })
    }

    fun `test the wheel skips section headings and group rows`() {
        // outline/08 R3
        val refactorings = openRefactorings()
        val outline = refactorings.awaitResult()
        awaitResult(outline) { state -> labels(state.items).any { it.contains("usages renamed") } }
        outline.setOutlineView(OutlineView.REFACTORINGS)
        awaitResult(outline) { state -> state.items.any { it.title == "Refactorings" } }
        select(outline, "rename total → sum, 1 usage  high")

        wheel(outline, 1.0)

        val selected = outline.panel.selectedItem()
        assertNotNull(selected)
        assertFalse("a section heading is never selected", selected!!.isGroup)
        assertEquals("class Order", selected.label)
        assertTrue(
            "a group row lies between the rename row and the selected row",
            hasGroupBetween(outline, "rename total → sum, 1 usage  high", selected.label),
        )

        val formatting = open(history(FORMATTED_OLD, FORMATTED_NEW, "Counter.java"))
        val formattingOutline = formatting.awaitResult()
        select(formattingOutline, "class Counter")
        wheel(formattingOutline, 1.0)
        assertEquals("method a()  changed", formattingOutline.panel.selectedItem()?.label)
        wheel(formattingOutline, 1.0)
        assertEquals("method a()  changed", formattingOutline.panel.selectedItem()?.label)
        val formattingSelected = formattingOutline.panel.selectedItem()
        assertNotNull(formattingSelected)
        assertFalse("a formatting-only group is never selected", formattingSelected!!.isGroup)
        assertTrue(
            "the formatting-only group row exists after method a() and was skipped",
            hasGroupAfter(formattingOutline, "method a()  changed"),
        )
    }

    fun `test the wheel stops at the first and last row`() {
        // outline/08 R4
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        select(outline, "class Greeter")
        wheel(outline, -1.0)
        assertEquals("class Greeter", outline.panel.selectedItem()?.label)

        select(outline, "method bye()  added")
        wheel(outline, 1.0)
        assertEquals("method bye()  added", outline.panel.selectedItem()?.label)
    }

    fun `test with no selection the wheel scrolls the outline`() {
        // outline/08 R5
        val shown = open(history(LONG_OLD, LONG_NEW, "Long.java"))
        val outline = shown.awaitResult()
        outline.panel.tree.selectionPath = null
        outline.panel.tree.clearSelection()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val scroll = scrollPane(outline)
        outline.panel.setSize(240, 80)
        outline.panel.validate()
        scroll.setSize(240, 80)
        scroll.doLayout()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val before = scroll.verticalScrollBar.value

        wheel(outline, 1.0, selectionRequired = false)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertNull(outline.panel.selectedItem())
        assertTrue(
            "the outline scrolled: before=$before now=${scroll.verticalScrollBar.value} max=${scroll.verticalScrollBar.maximum}",
            scroll.verticalScrollBar.maximum > scroll.verticalScrollBar.visibleAmount && scroll.verticalScrollBar.value > before,
        )
    }

    fun `test small precise wheel events move one row per full notch`() {
        // outline/08 R6
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        select(outline, "class Greeter")

        wheel(outline, 0.3)
        assertEquals("class Greeter", outline.panel.selectedItem()?.label)
        wheel(outline, 0.3)
        assertEquals("class Greeter", outline.panel.selectedItem()?.label)
        wheel(outline, 0.4)
        assertEquals("method hello()  changed", outline.panel.selectedItem()?.label)

        wheel(outline, 0.5)
        wheel(outline, 0.5)
        assertEquals("method bye()  added", outline.panel.selectedItem()?.label)
    }

    fun `test a horizontal or shift wheel scroll does not change the selection`() {
        // outline/08 R7
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        select(outline, "method hello()  changed")

        val scroll = scrollPane(outline)
        outline.panel.setSize(120, 120)
        outline.panel.validate()
        outline.panel.tree.preferredSize = Dimension(800, outline.panel.tree.preferredSize.height.coerceAtLeast(200))
        scroll.setSize(120, 120)
        scroll.doLayout()
        scroll.horizontalScrollBar.unitIncrement = 40
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(
            "the outline has room to scroll horizontally",
            scroll.horizontalScrollBar.maximum > scroll.horizontalScrollBar.visibleAmount,
        )

        val before = scroll.horizontalScrollBar.value
        wheel(outline, 0.5, modifiers = InputEvent.SHIFT_DOWN_MASK, wheelRotationOverride = 0)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("method hello()  changed", outline.panel.selectedItem()?.label)

        wheel(outline, 1.0, modifiers = InputEvent.SHIFT_DOWN_MASK)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("method hello()  changed", outline.panel.selectedItem()?.label)
        assertTrue(
            "a horizontal or shift wheel scroll moved the outline horizontally",
            scroll.horizontalScrollBar.value > before,
        )
    }

    fun `test stepping does not move focus`() {
        // outline/08 R8
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        select(outline, "method hello()  changed")
        shown.viewer.editor2.contentComponent.requestFocusInWindow()
        // In a headless test focus may not transfer to the editor; comparing before/after guards against requestFocus calls.
        val focusBefore = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner

        wheel(outline, 1.0)

        assertEquals("method bye()  added", outline.panel.selectedItem()?.label)
        val focusAfter = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        assertSame("the wheel step did not move focus", focusBefore, focusAfter)
        assertFalse("the outline tree did not take focus", outline.panel.tree.isFocusOwner)
    }

    private inner class Shown(val panel: DiffRequestPanel, val disposable: Disposable, val request: SimpleDiffRequest, val viewer: TwosideTextDiffViewer) {
        val outline: OutlineController = OutlineController.of(viewer) ?: error("no outline attached to ${request.title}")
        fun awaitResult(): OutlineController = outline.also { awaitResult(it) { true } }
    }

    private fun open(request: SimpleDiffRequest): Shown {
        val disposable = Disposer.newDisposable(testRootDisposable)
        val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        panel.setRequest(request)
        return Shown(panel, disposable, request, awaitViewer(request) as TwosideTextDiffViewer)
    }

    private fun openRefactorings(): Shown {
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        return open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
    }

    private fun awaitViewer(request: DiffRequest): FrameDiffTool.DiffViewer {
        PlatformTestUtil.waitWithEventsDispatching("no diff viewer was created", { created.any { it.first === request } }, 10)
        return created.last { it.first === request }.second
    }

    private fun awaitResult(outline: OutlineController, condition: (OutlineState.Shown) -> Boolean = { true }): OutlineState.Shown {
        PlatformTestUtil.waitWithEventsDispatching(
            { "no matching outline; state is ${outline.state}" },
            { (outline.state as? OutlineState.Shown)?.let(condition) == true },
            10,
        )
        return outline.state as OutlineState.Shown
    }

    private fun history(old: String, new: String, fileName: String) = SimpleDiffRequest(
        fileName,
        DiffContents.vcsRevision(project, old, "/repo/$fileName"),
        DiffContents.vcsRevision(project, new, "/repo/$fileName"),
        "abc123",
        "def456",
    )

    private fun select(outline: OutlineController, label: String) {
        val root = outline.panel.tree.model.root as DefaultMutableTreeNode
        val node = root.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }
            .single { (it.userObject as? OutlineItem)?.label == label }
        outline.panel.tree.selectionPath = TreePath(node.path)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun wheel(
        outline: OutlineController,
        rotation: Double,
        modifiers: Int = 0,
        selectionRequired: Boolean = true,
        wheelRotationOverride: Int? = null,
    ) {
        val tree = outline.panel.tree
        if (selectionRequired) requireNotNull(tree.selectionPath)
        val wheelRotation = wheelRotationOverride ?: when {
            rotation > 0.0 -> 1
            rotation < 0.0 -> -1
            else -> 0
        }
        val event = MouseWheelEvent(
            tree,
            MouseWheelEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            modifiers,
            5,
            5,
            5,
            5,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1,
            wheelRotation,
            rotation,
        )
        tree.dispatchEvent(event)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun labels(items: List<OutlineItem>): List<String> = items.flatMap { listOf(it.label) + labels(it.children) }

    private fun visibleItems(outline: OutlineController): List<OutlineItem> =
        (0 until outline.panel.tree.rowCount).mapNotNull {
            (outline.panel.tree.getPathForRow(it)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? OutlineItem
        }

    private fun hasGroupBetween(outline: OutlineController, fromLabel: String, toLabel: String): Boolean {
        val items = visibleItems(outline)
        val from = items.indexOfFirst { it.label == fromLabel }
        val to = items.indexOfFirst { it.label == toLabel }
        assertTrue("rows for $fromLabel and $toLabel are visible", from >= 0 && to >= 0)
        val range = if (from < to) (from + 1) until to else (to + 1) until from
        return range.any { items[it].isGroup }
    }

    private fun hasGroupAfter(outline: OutlineController, fromLabel: String): Boolean {
        val items = visibleItems(outline)
        val from = items.indexOfFirst { it.label == fromLabel }
        assertTrue("row for $fromLabel is visible", from >= 0)
        return (from + 1 until items.size).any { items[it].isGroup }
    }

    private fun scrollPane(outline: OutlineController): javax.swing.JScrollPane =
        javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane::class.java, outline.panel.tree) as javax.swing.JScrollPane

    private companion object {
        const val JAVA_OLD = "class Greeter {\n    String hello() { return \"hi\"; }\n}\n"
        const val JAVA_NEW = "class Greeter {\n    String hello() { return \"hello\"; }\n\n    void bye() {}\n}\n"
        const val FORMATTED_OLD = "class Counter {\n    int a() { return 1; }\n\n    int b() { return 2; }\n}\n"
        const val FORMATTED_NEW = "class Counter {\n    int a() { return 3; }\n\n    int b() {\n        return 2;\n    }\n}\n"
        const val USAGE_OLD = "class Order {\n    int total() { return 2; }\n    int twice() { return total() * 2; }\n}\n"
        const val USAGE_NEW = "class Order {\n    int sum() { return 2; }\n    int twice() { return sum() * 2; }\n}\n"
        val LONG_OLD = "class Long {\n" + (1..80).joinToString("\n") { "    int a$it() { return $it; }" } + "\n}\n"
        val LONG_NEW = "class Long {\n" + (1..80).joinToString("\n") { "    int a$it() { return ${it + 1}; }" } + "\n}\n"
    }
}
