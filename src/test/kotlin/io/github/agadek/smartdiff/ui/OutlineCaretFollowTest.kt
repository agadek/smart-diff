package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipEndpoint
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import io.github.agadek.smartdiff.references.ReferencePass
import java.awt.KeyboardFocusManager
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * Outline caret follow (outline/07): a real two-sided diff viewer drives the outline, and the debounce timer is fired
 * at once through [OutlineController.fireCaretFollow].
 */
class OutlineCaretFollowTest : BasePlatformTestCase() {

    private val created = mutableListOf<Pair<DiffRequest, FrameDiffTool.DiffViewer>>()
    private val settings get() = SmartDiffSettings.getInstance()
    private var savedSettings: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        savedSettings = settings.state
        val extension = object : DiffExtension() {
            override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
                created += request to viewer
            }
        }
        ExtensionTestUtil.addExtensions(DiffExtension.EP_NAME, listOf(extension), testRootDisposable)
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

    fun `test the caret on the new side selects the changed method under it`() {
        // outline/07 R1, R2
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()

        moveCaret(outline, OutlineController.CaretSide.NEW, JAVA_NEW.indexOf("String hello()") + 2)
        outline.fireCaretFollow()

        assertEquals("method hello()  changed", outline.panel.selectedItem()?.label)
    }

    fun `test the caret on the old side selects by the old range`() {
        // outline/07 R1, R2
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()

        moveCaret(outline, OutlineController.CaretSide.OLD, JAVA_OLD.indexOf("String hello()") + 2)
        outline.fireCaretFollow()

        assertEquals("method hello()  changed", outline.panel.selectedItem()?.label)
    }

    fun `test a nested declaration selects the innermost row`() {
        // outline/07 R1, R2
        val shown = open(history(NESTED_OLD, NESTED_NEW, "Outer.java"))
        val outline = shown.awaitResult()

        moveCaret(outline, OutlineController.CaretSide.NEW, NESTED_NEW.indexOf("void inner()") + 2)
        outline.fireCaretFollow()

        assertEquals("method inner()  changed", outline.panel.selectedItem()?.label)
    }

    fun `test a caret outside every changed declaration keeps the selection`() {
        // outline/07 R3
        val old = """
            class Unchanged {
                void stay() { }
            }

            class Greeter {
                String hello() { return "hi"; }
            }
        """.trimIndent() + "\n"
        val new = """
            class Unchanged {
                void stay() { }
            }

            class Greeter {
                String hello() { return "hello"; }
            }
        """.trimIndent() + "\n"
        val shown = open(history(old, new, "Greeter.java"))
        val outline = shown.awaitResult()
        select(outline, "method hello()  changed")

        moveCaret(outline, OutlineController.CaretSide.NEW, new.indexOf("void stay()") + 2)
        outline.fireCaretFollow()

        assertEquals("method hello()  changed", outline.panel.selectedItem()?.label)
    }

    fun `test following frames the row without scrolling either editor or moving focus`() {
        // outline/07 R4
        val shown = open(history(SCROLL_OLD, SCROLL_NEW, "LongFile.java"))
        val outline = shown.awaitResult()
        val old = outline.viewer.editor1
        val new = outline.viewer.editor2
        showEditor(old)
        showEditor(new)
        old.scrollingModel.scrollVertically(0)
        new.scrollingModel.scrollVertically(0)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val oldArea = old.scrollingModel.visibleArea
        val newArea = new.scrollingModel.visibleArea
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner

        moveCaret(outline, OutlineController.CaretSide.NEW, SCROLL_NEW.indexOf("void tail()") + 2)
        outline.fireCaretFollow()

        assertEquals("method tail()  changed", outline.panel.selectedItem()?.label)
        assertEquals("the old editor must not scroll", oldArea, old.scrollingModel.visibleArea)
        assertEquals("the new editor must not scroll", newArea, new.scrollingModel.visibleArea)
        assertEquals("focus must stay where it is", focus, KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner)
        assertTrue(outline.navigator.shown.isNotEmpty())
    }

    fun `test following leaves the selection alone while the outline has focus`() {
        // outline/07 R5
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        select(outline, "method hello()  changed")
        outline.outlineFocusedOverride = true

        moveCaret(outline, OutlineController.CaretSide.NEW, JAVA_NEW.indexOf("void bye()") + 2)
        outline.fireCaretFollow()

        assertEquals("method hello()  changed", outline.panel.selectedItem()?.label)
    }

    fun `test caret moves are debounced`() {
        // outline/07 R5
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        // Fire once to settle initial follow state.
        outline.fireCaretFollow()
        select(outline, "method hello()  changed")

        moveCaret(outline, OutlineController.CaretSide.NEW, JAVA_NEW.indexOf("void bye()") + 2)
        // The timer has not fired yet, so the selection must not change.
        assertEquals("method hello()  changed", outline.panel.selectedItem()?.label)

        outline.fireCaretFollow()
        assertEquals("method bye()  added", outline.panel.selectedItem()?.label)
    }

    fun `test the refactorings view selects the row under Other changes`() {
        // outline/07 R6
        val old = Subject.Declaration(DeclarationKind.CLASS, "Foo", "")
        val new = Subject.Declaration(DeclarationKind.CLASS, "Bar", "")
        val range = TextRange(0, 10)
        val engine = ResultEngine().apply {
            status = Status.ANALYZED
            changes = listOf(
                ChangeNode(new, range, range, Presence.MATCHED, content = Content.CHANGED, renamedFrom = old),
            )
            relationships = listOf(
                Relationship(
                    EnumSet.of(RelationshipKind.RENAME),
                    listOf(RelationshipEndpoint(old, range)),
                    listOf(RelationshipEndpoint(new, range)),
                    Confidence.HIGH,
                    listOf(Evidence.Name("Foo", "Bar")),
                ),
            )
        }
        maskEngines(engine)
        val shown = open(history("class Foo {}", "class Bar {}", "Foo.java"))
        val outline = shown.awaitResult()
        outline.setOutlineView(OutlineView.REFACTORINGS)
        awaitRefactorings(outline)

        val offset = 1
        moveCaret(outline, OutlineController.CaretSide.NEW, offset)
        outline.fireCaretFollow()

        val selected = outline.panel.selectedItem()
        assertNotNull(selected)
        assertNotSame(ItemKind.RELATIONSHIP, selected!!.kind)

        val selectedNode = selectedNode(outline)
        val parent = selectedNode.parent as? DefaultMutableTreeNode
        val parentItem = parent?.userObject as? OutlineItem
        assertEquals(RefactoringsModel.OTHER_CHANGES, parentItem?.title)

        val selectedRange = selected.newRange ?: error("selected row has no new range")
        val all = allItems((outline.state as OutlineState.Shown).items)
        val competing = all.filter {
            it.kind == ItemKind.RELATIONSHIP && it.newRange != null &&
                it.newRange!!.start <= offset && offset < it.newRange!!.end &&
                (it.newRange!!.end - it.newRange!!.start) == (selectedRange.end - selectedRange.start)
        }
        assertTrue(
            "expected an equal-range RELATIONSHIP row containing the caret; rows=${all.map { "${it.kind}:${it.title}:${it.newRange}" }}",
            competing.isNotEmpty(),
        )
    }

    fun `test a row hidden by the filter is never selected`() {
        // outline/07 R7
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        outline.setHidden(ChangeType.ADDED, true)

        moveCaret(outline, OutlineController.CaretSide.NEW, JAVA_NEW.indexOf("void bye()") + 2)
        outline.fireCaretFollow()

        val selected = outline.panel.selectedItem()?.label
        assertTrue(selected == null || selected != "method bye()  added")
    }

    fun `test a stale result is not followed until the new result is shown`() {
        // outline/07 R8
        val local = myFixture.addFileToProject("local/Greeter.java", JAVA_NEW).virtualFile
        val request = SimpleDiffRequest(
            "local",
            DiffContents.vcsRevision(project, JAVA_OLD, "/repo/Greeter.java"),
            DiffContents.localFile(project, local),
            "HEAD",
            "Current",
        )
        val shown = open(request)
        val outline = shown.awaitResult()
        select(outline, "method hello()  changed")

        // Make the result stale; the comparison is pending while the debounce runs.
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            outline.viewer.editor2.document.insertString(0, "// edit\n")
        }
        val edited = "// edit\n" + JAVA_NEW
        moveCaret(outline, OutlineController.CaretSide.NEW, edited.indexOf("void bye()") + 2)
        outline.fireCaretFollow()
        assertTrue("method bye()  added" != outline.panel.selectedItem()?.label)

        outline.refresh()
        PlatformTestUtil.waitWithEventsDispatching(
            { "caret not followed after new result; selection=${outline.panel.selectedItem()?.label}, state=${outline.state}" },
            { outline.panel.selectedItem()?.label == "method bye()  added" },
            10,
        )
    }

    fun `test the caret is followed while the reference pass runs`() {
        // outline/07 R8
        val local = myFixture.addFileToProject("local/Greeter.java", JAVA_NEW).virtualFile
        val request = SimpleDiffRequest(
            "local",
            DiffContents.vcsRevision(project, JAVA_OLD, "/repo/Greeter.java"),
            DiffContents.localFile(project, local),
            "HEAD",
            "Current",
        )
        val shown = open(request)
        val outline = shown.awaitResult()
        val release = CountDownLatch(1)
        outline.passRunner = { _, result, _ ->
            release.await(10, TimeUnit.SECONDS)
            ReferencePass.Outcome(result, ReferencePass.Status.NOT_GROUPED)
        }
        outline.refresh()
        awaitResult(outline) { it.usageNote == OutlineController.RESOLVING }

        moveCaret(outline, OutlineController.CaretSide.NEW, JAVA_NEW.indexOf("void bye()") + 2)
        outline.fireCaretFollow()

        assertEquals("method bye()  added", outline.panel.selectedItem()?.label)

        release.countDown()
        PlatformTestUtil.waitWithEventsDispatching(
            { "reference pass did not finish; note=${(outline.state as? OutlineState.Shown)?.usageNote}" },
            { (outline.state as? OutlineState.Shown)?.usageNote == null },
            10,
        )
        assertEquals("method bye()  added", outline.panel.selectedItem()?.label)
    }

    private fun awaitRefactorings(outline: OutlineController) {
        PlatformTestUtil.waitWithEventsDispatching(
            { "refactorings view not ready; state=${outline.state}" },
            { (outline.state as? OutlineState.Shown)?.items?.firstOrNull()?.title == "Refactorings" },
            10,
        )
    }

    private inner class Shown(val viewer: FrameDiffTool.DiffViewer) {
        val outline: OutlineController = OutlineController.of(viewer) ?: error("no outline attached")
        fun awaitResult(): OutlineController = this@OutlineCaretFollowTest.awaitResult(outline).let { outline }
    }

    private fun open(request: SimpleDiffRequest): Shown {
        val panel = DiffManager.getInstance().createRequestPanel(project, Disposer.newDisposable(testRootDisposable), null)
        panel.setRequest(request)
        return Shown(awaitViewer(request))
    }

    private fun awaitViewer(request: DiffRequest): FrameDiffTool.DiffViewer {
        PlatformTestUtil.waitWithEventsDispatching("no diff viewer was created", { created.any { it.first === request } }, 10)
        return created.last { it.first === request }.second
    }

    private fun awaitResult(outline: OutlineController): OutlineState.Shown =
        awaitResult(outline) { true }

    private fun awaitResult(outline: OutlineController, condition: (OutlineState.Shown) -> Boolean): OutlineState.Shown {
        PlatformTestUtil.waitWithEventsDispatching(
            { "no matching result; state is ${outline.state}" },
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
    }

    private fun selectedNode(outline: OutlineController): DefaultMutableTreeNode =
        outline.panel.tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: error("no selected node")

    private fun allItems(items: List<OutlineItem>): List<OutlineItem> =
        items.flatMap { listOf(it) + allItems(it.children) }

    private fun moveCaret(outline: OutlineController, side: OutlineController.CaretSide, offset: Int) {
        val editor = if (side == OutlineController.CaretSide.OLD) outline.viewer.editor1 else outline.viewer.editor2
        ApplicationManager.getApplication().invokeAndWait {
            editor.caretModel.moveToOffset(offset.coerceIn(0, editor.document.textLength))
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun showEditor(editor: com.intellij.openapi.editor.Editor) {
        com.intellij.testFramework.EditorTestUtil.setEditorVisibleSize(editor, 100, 14)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun factory(engine: ComparisonEngine) = object : ComparisonEngineFactory {
        override fun create(project: Project?): ComparisonEngine = engine
    }

    private fun maskEngines(vararg engines: ComparisonEngine) {
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, engines.map(::factory), testRootDisposable)
    }

    private class ResultEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.result")
        override val displayName = "Result (test)"
        override val version = "1"

        @Volatile var status = Status.FAILED
        @Volatile var reason: String? = null
        @Volatile var changes: List<ChangeNode> = emptyList()
        @Volatile var unanalyzed: List<io.github.agadek.smartdiff.engine.api.RangePair> = emptyList()
        @Volatile var relationships: List<Relationship> = emptyList()

        override fun supports(languageId: String) = true

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult =
            ComparisonResult(id, version, status, changes = changes, unanalyzed = unanalyzed, fallbackReason = reason, relationships = relationships)
    }

    private companion object {
        const val JAVA_OLD = "class Greeter {\n    String hello() { return \"hi\"; }\n}\n"
        const val JAVA_NEW = "class Greeter {\n    String hello() { return \"hello\"; }\n\n    void bye() {}\n}\n"

        val NESTED_OLD = """
            class Outer {
                class Inner {
                    void inner() { int x = 1; }
                }
            }
        """.trimIndent() + "\n"
        val NESTED_NEW = """
            class Outer {
                class Inner {
                    void inner() { int x = 2; }
                }
            }
        """.trimIndent() + "\n"

        val SCROLL_OLD: String = buildString {
            append("class LongFile {\n")
            repeat(80) { append("    void m$it() { int a = $it; }\n") }
            append("    void tail() { int a = 1; }\n")
            append("}\n")
        }
        val SCROLL_NEW: String = buildString {
            append("class LongFile {\n")
            repeat(80) { append("    void m$it() { int a = $it; }\n") }
            append("    void tail() { int a = 2; }\n")
            append("}\n")
        }

        val EXTRACTION_OLD = """
            class Order {
                String render(String name) {
                    return heading(name) + "!";
                }

                private String heading(String name) {
                    String h = "Report: " + name;
                    return h;
                }
            }
        """.trimIndent() + "\n"
        val EXTRACTION_NEW = """
            class Order {
                String render(String name) {
                    String h = "Report: " + name;
                    return h + "!";
                }
            }
        """.trimIndent() + "\n"
    }
}
