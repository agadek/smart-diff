package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.impl.DiffSettingsHolder.DiffSettings
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffChange
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
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
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.lines.LineEngine
import io.github.agadek.smartdiff.engine.lines.LineEngineFactory
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import io.github.agadek.smartdiff.engine.registry.EngineDefaults
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * The outline in IntelliJ's own diff (technical specification §12 integration test and abstraction test): diffs are
 * opened through `DiffManager.createRequestPanel`, which runs the same request processor as diff windows and editor tabs.
 */
class OutlineIntegrationTest : BasePlatformTestCase() {

    private val created = mutableListOf<Pair<DiffRequest, FrameDiffTool.DiffViewer>>()
    private val settings get() = SmartDiffSettings.getInstance()
    private var savedSettings: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        savedSettings = settings.state
        // Registered after the plugin's own extension, so the outline is attached when this one runs.
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
            listOf("JAVA", "kotlin").forEach { EngineDefaults.getInstance().set(it, null) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test a Java diff shows the outline and selecting a node highlights both sides`() {
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()

        assertTrue(UIUtil.isAncestor(shown.viewer.component, outline.panel))
        assertTrue(UIUtil.isAncestor(shown.viewer.component, shown.viewer.editor1.component))
        assertTrue(UIUtil.isAncestor(shown.viewer.component, shown.viewer.editor2.component))
        assertEquals("1 changed · 1 added", outline.panel.summary.text)
        assertEquals(listOf("class Greeter", "  method hello()  changed", "  method bye()  added"), rows(outline))

        select(outline, "method hello()  changed")
        assertHighlightedAt(shown.viewer.editor1, JAVA_OLD.indexOf("String hello()"))
        assertHighlightedAt(shown.viewer.editor2, JAVA_NEW.indexOf("String hello()"))
        assertEquals("", outline.panel.absence.text)
        assertEquals("each side gets a framed range, marked on the scrollbar", 2, outline.navigator.shown.count { it.customRenderer != null && it.getErrorStripeMarkColor(null) != null })

        select(outline, "method bye()  added")
        assertTrue("an absent side shows no highlight", outline.navigator.shown.none { it.document === shown.viewer.editor1.document })
        assertHighlightedAt(shown.viewer.editor2, JAVA_NEW.indexOf("void bye()"))
        assertEquals("Not present on the old side", outline.panel.absence.text)

        outline.navigator.navigate(outline.panel.selectedItem()!!)
        assertEquals(JAVA_NEW.indexOf("void bye()"), shown.viewer.editor2.caretModel.offset)
    }

    fun `test hiding a change type rebuilds the outline and is remembered`() {
        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()

        outline.setHidden(ChangeType.ADDED, true)

        assertEquals(setOf(ChangeType.ADDED), settings.hiddenChangeTypes)
        assertEquals(listOf("class Greeter", "  method hello()  changed"), rows(outline))
        assertEquals("1 changed · 1 added · 1 hidden", outline.panel.summary.text)

        outline.setHidden(ChangeType.CHANGED, true)
        assertEquals(emptyList<String>(), rows(outline))
        assertEquals("All changes are hidden by the filter", outline.panel.tree.emptyText.text)

        // Another diff starts with the same filter.
        assertEquals(emptyList<String>(), rows(open(history(KOTLIN_OLD, KOTLIN_NEW, "Greeter.kt")).awaitResult()))

        outline.setHidden(ChangeType.ADDED, false)
        outline.setHidden(ChangeType.CHANGED, false)
        assertEquals(listOf("class Greeter", "  method hello()  changed", "  method bye()  added"), rows(outline))
    }

    fun `test the SMART Diff viewer fades the changes the filter hides`() {
        val diffSettings = DiffSettings.getSettings()
        val savedOrder = diffSettings.diffToolsOrder
        try {
            assertContainsElements(DiffManagerEx.getInstance().diffTools.map { it.name }, FilteringDiffTool.NAME)
            diffSettings.diffToolsOrder = listOf(FilteringDiffTool::class.java.canonicalName) + savedOrder
            val shown = open(history(FORMATTED_OLD, FORMATTED_NEW, "Counter.java"))
            val viewer = shown.viewer as FilteringDiffViewer
            val outline = shown.awaitResult()
            awaitChanges(viewer) { it.size == 2 }
            assertTrue("nothing is faded while nothing is hidden", viewer.diffChanges.none { it.isSkipped })

            outline.setHidden(ChangeType.FORMATTING_ONLY, true)

            awaitChanges(viewer) { changes -> changes.any { it.isSkipped } }
            val (faded, kept) = viewer.diffChanges.partition { it.isSkipped }
            assertEquals("the reformatted method", listOf(3), faded.map { it.getStartLine(Side.RIGHT) })
            assertEquals("the changed method", 1, kept.size)

            outline.setOutlineVisible(false)
            awaitChanges(viewer) { changes -> changes.size == 2 && changes.none { it.isSkipped } }
        } finally {
            diffSettings.diffToolsOrder = savedOrder
        }
    }

    fun `test losing the engine clears faded changes and shows NoEngine`() {
        val diffSettings = DiffSettings.getSettings()
        val savedOrder = diffSettings.diffToolsOrder
        val registration = Disposer.newDisposable(testRootDisposable)
        try {
            ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(LineEngineFactory()), registration)
            diffSettings.diffToolsOrder = listOf(FilteringDiffTool::class.java.canonicalName) + savedOrder

            // LineEngine supports TEXT, so the plain-text skip does not apply and the result can be shown first.
            val viewer = openAny(history("one\n", "two\n", "notes.txt")) as FilteringDiffViewer
            val outline = OutlineController.of(viewer)!!
            awaitResult(outline) { it.result.engineId == LineEngine.ID }
            outline.setHidden(ChangeType.CHANGED, true)
            awaitChanges(viewer) { changes -> changes.any { it.isSkipped } }

            Disposer.dispose(registration)
            outline.refresh()

            val languageName = requireNotNull(Language.findLanguageByID("TEXT")?.displayName)
            assertEquals(OutlineState.NoEngine(languageName), outline.state)
            assertEquals("the no-engine message shows the native diff without faded marks", HiddenLines.NONE, viewer.hiddenLines)
        } finally {
            diffSettings.diffToolsOrder = savedOrder
        }
    }

    fun `test a Kotlin diff shows the outline`() {
        val outline = open(history(KOTLIN_OLD, KOTLIN_NEW, "Greeter.kt")).awaitResult()

        assertEquals(listOf("class Greeter", "  function hello()  changed", "  function bye()  added"), rows(outline))
    }

    fun `test the outline attaches to local-change and compare-with diffs`() {
        for ((fileName, old, new) in listOf(Triple("Greeter.java", JAVA_OLD, JAVA_NEW), Triple("Greeter.kt", KOTLIN_OLD, KOTLIN_NEW))) {
            val local = DiffContents.localFile(project, myFixture.addFileToProject("local/$fileName", new).virtualFile)
            val localChange = SimpleDiffRequest("local change", DiffContents.vcsRevision(project, old, "/repo/$fileName"), local, "HEAD", "Current")
            assertEquals(fileName, "1 changed · 1 added", open(localChange).awaitResult().panel.summary.text)

            val compareWith = SimpleDiffRequest(
                "compare",
                DiffContents.localFile(project, myFixture.addFileToProject("a/$fileName", old).virtualFile),
                DiffContents.localFile(project, myFixture.addFileToProject("b/$fileName", new).virtualFile),
                "a",
                "b",
            )
            assertEquals(fileName, "1 changed · 1 added", open(compareWith).awaitResult().panel.summary.text)
        }
    }

    fun `test a revision with CRLF line ends is compared as LF text`() {
        val shown = open(history(JAVA_OLD.replace("\n", "\r\n"), JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()

        assertEquals(listOf("class Greeter", "  method hello()  changed", "  method bye()  added"), rows(outline))
        select(outline, "method hello()  changed")
        assertHighlightedAt(shown.viewer.editor1, JAVA_OLD.indexOf("String hello()"))
    }

    fun `test the toggle hides the outline and shows the native diff`() {
        val action = ActionManager.getInstance().getAction("SmartDiff.ToggleOutline") as ToggleOutlineAction
        val toolbar = ActionManager.getInstance().getAction(IdeActions.DIFF_VIEWER_TOOLBAR) as DefaultActionGroup
        assertContainsElements(toolbar.childActionsOrStubs.toList(), action)

        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getSimpleContext(DiffDataKeys.DIFF_VIEWER, shown.viewer))
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        assertTrue(action.isSelected(event))

        action.setSelected(event, false)

        assertFalse(UIUtil.isAncestor(shown.viewer.component, outline.panel))
        assertTrue(UIUtil.isAncestor(shown.viewer.component, shown.viewer.editor1.component))
        assertTrue(UIUtil.isAncestor(shown.viewer.component, shown.viewer.editor2.component))
        assertEquals(OutlineState.Hidden, outline.state)
        assertFalse(action.isSelected(event))

        // The choice applies to this diff window: the next file in it starts without the outline, another window with it.
        val next = shown.show(history(KOTLIN_OLD, KOTLIN_NEW, "Greeter.kt"))
        assertFalse(next.outline.isOutlineVisible)
        assertEquals(OutlineState.Hidden, next.outline.state)
        assertTrue(open(history(JAVA_OLD, JAVA_NEW, "Other.java")).outline.isOutlineVisible)

        action.setSelected(TestActionEvent.createTestEvent(action, SimpleDataContext.getSimpleContext(DiffDataKeys.DIFF_VIEWER, next.viewer)), true)
        assertTrue(UIUtil.isAncestor(next.viewer.component, next.outline.panel))
        next.awaitResult()
    }

    fun `test the outline starts hidden when the setting is off`() {
        settings.showOutline = false

        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))

        assertFalse(shown.outline.isOutlineVisible)
        assertEquals(OutlineState.Hidden, shown.outline.state)
        assertFalse(UIUtil.isAncestor(shown.viewer.component, shown.outline.panel))
    }

    fun `test a test-only engine appears in the dropdown and can be selected`() {
        ExtensionTestUtil.addExtensions(ComparisonEngineFactory.EP_NAME, listOf(LineEngineFactory()), testRootDisposable)

        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()
        val engines = outline.panel.engines
        assertTrue("the dropdown is shown when two engines apply", engines.isVisible)
        assertEquals(listOf("Declarations", "Lines (test)"), (0 until engines.itemCount).map { engines.getItemAt(it).displayName })
        assertEquals("Declarations", (engines.selectedItem as ComparisonEngine).displayName)

        engines.selectedItem = (0 until engines.itemCount).map { engines.getItemAt(it) }.single { it.id == LineEngine.ID }

        val result = awaitResult(outline) { it.result.engineId == LineEngine.ID }
        assertEquals(LineEngine.ID, result.result.engineId)
        assertEquals(listOf("lines 2 changed to 2-4  changed"), rows(outline))
        assertEquals("the choice is stored for the language", LineEngine.ID, EngineDefaults.getInstance().get("JAVA"))
    }

    fun `test the dropdown is hidden with a single engine`() {
        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()

        assertFalse(outline.panel.engines.isVisible)
    }

    fun `test editing a side compares again`() {
        val local = myFixture.addFileToProject("local/Greeter.java", JAVA_NEW).virtualFile
        val shown = open(SimpleDiffRequest("local", DiffContents.vcsRevision(project, JAVA_OLD, "/repo/Greeter.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
        val outline = shown.awaitResult()
        assertEquals("1 changed · 1 added", outline.panel.summary.text)

        val document = (shown.request.contents[1] as DocumentContent).document
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.text.lastIndexOf("}"), "    void more() {}\n")
        }

        awaitResult(outline) { it.summary == "1 changed · 2 added" }
    }

    fun `test a result over the size limit shows a status message`() {
        settings.limits = Limits.DEFAULT.copy(maxChars = 10)

        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()

        assertEquals(Status.TOO_LARGE, (outline.state as OutlineState.Shown).result.status)
        assertTrue(outline.panel.message.text, outline.panel.message.text.contains("Too large to analyze"))
        assertEquals(emptyList<String>(), rows(outline))
    }

    fun `test a comparison over the time limit is canceled and shown as failed`() {
        val engine = SlowEngine()
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(factory(engine)), testRootDisposable)
        settings.limits = Limits.DEFAULT.copy(timeoutMillis = 100)

        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()

        val result = (outline.state as OutlineState.Shown).result
        assertEquals(Status.FAILED, result.status)
        assertEquals("timed out after 100 ms", result.fallbackReason)
        assertTrue(outline.panel.message.text, outline.panel.message.text.contains("timed out"))
        assertTrue(engine.canceled)
    }

    fun `test disposing the viewer cancels the comparison and releases the outline`() {
        val engine = SlowEngine()
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(factory(engine)), testRootDisposable)

        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        assertEquals(OutlineState.Loading, shown.outline.state)
        PlatformTestUtil.waitWithEventsDispatching("the engine did not start", { engine.started }, 10)

        Disposer.dispose(shown.disposable)

        PlatformTestUtil.waitWithEventsDispatching("the comparison was not canceled", { engine.canceled }, 10)
        assertEquals(OutlineState.Hidden, shown.outline.state)
        assertNull(OutlineController.of(shown.viewer))
    }

    fun `test a language without an engine shows the outline with an unsupported message`() {
        // The light test IDE loads neither properties nor YAML; mask the engines so Kotlin, which is loaded, has none.
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(factory(JavaOnlyEngine())), testRootDisposable)
        val viewer = openAny(history(KOTLIN_OLD, KOTLIN_NEW, "Greeter.kt")) as TwosideTextDiffViewer
        val outline = OutlineController.of(viewer)
        assertNotNull("the outline is attached", outline)
        val controller = outline!!

        val languageName = requireNotNull(Language.findLanguageByID("kotlin")?.displayName)
        val state = controller.state as OutlineState.NoEngine
        assertEquals(languageName, state.languageName)
        assertTrue(controller.panel.message.text, controller.panel.message.text.contains(languageName))

        assertTrue(UIUtil.isAncestor(viewer.component, viewer.editor1.component))
        assertTrue(UIUtil.isAncestor(viewer.component, viewer.editor2.component))

        val action = ActionManager.getInstance().getAction("SmartDiff.ToggleOutline") as ToggleOutlineAction
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getSimpleContext(DiffDataKeys.DIFF_VIEWER, viewer))
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        assertTrue(action.isSelected(event))

        action.setSelected(event, false)
        assertFalse(UIUtil.isAncestor(viewer.component, controller.panel))
        assertEquals(OutlineState.Hidden, controller.state)
        assertFalse(action.isSelected(event))

        action.setSelected(event, true)
        assertTrue(UIUtil.isAncestor(viewer.component, controller.panel))
        assertEquals(languageName, (controller.state as OutlineState.NoEngine).languageName)
        assertTrue(controller.panel.message.text, controller.panel.message.text.contains(languageName))
    }

    fun `test a plain-text diff gets no outline`() {
        val viewer = openAny(history("one\n", "two\n", "notes.txt"))

        assertNull(OutlineController.of(viewer))
        val action = ActionManager.getInstance().getAction("SmartDiff.ToggleOutline") as ToggleOutlineAction
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getSimpleContext(DiffDataKeys.DIFF_VIEWER, viewer))
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
    }

    private inner class Shown(val panel: DiffRequestPanel, val disposable: Disposable, val request: SimpleDiffRequest, val viewer: TwosideTextDiffViewer) {
        val outline: OutlineController = OutlineController.of(viewer) ?: error("no outline attached to ${request.title}")

        fun awaitResult(): OutlineController = outline.also { awaitResult(it) { true } }

        /** Shows [next] in the same diff panel, as a diff window does when the user moves to the next file. */
        fun show(next: SimpleDiffRequest): Shown {
            panel.setRequest(next)
            return Shown(panel, disposable, next, awaitViewer(next) as TwosideTextDiffViewer)
        }
    }

    private fun open(request: SimpleDiffRequest): Shown {
        val disposable = Disposer.newDisposable(testRootDisposable)
        val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        panel.setRequest(request)
        return Shown(panel, disposable, request, awaitViewer(request) as TwosideTextDiffViewer)
    }

    private fun openAny(request: SimpleDiffRequest): FrameDiffTool.DiffViewer {
        val panel = DiffManager.getInstance().createRequestPanel(project, Disposer.newDisposable(testRootDisposable), null)
        panel.setRequest(request)
        return awaitViewer(request)
    }

    private fun awaitViewer(request: DiffRequest): FrameDiffTool.DiffViewer {
        PlatformTestUtil.waitWithEventsDispatching("no diff viewer was created", { created.any { it.first === request } }, 10)
        return created.last { it.first === request }.second
    }

    private fun awaitResult(outline: OutlineController, condition: (OutlineState.Shown) -> Boolean): OutlineState.Shown {
        PlatformTestUtil.waitWithEventsDispatching(
            { "no matching outline; state is ${outline.state}" },
            { (outline.state as? OutlineState.Shown)?.let(condition) == true },
            10,
        )
        return outline.state as OutlineState.Shown
    }

    private fun awaitChanges(viewer: FilteringDiffViewer, condition: (List<SimpleDiffChange>) -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching(
            { "no matching changes; they are ${viewer.diffChanges.map { "${it.getStartLine(Side.RIGHT)} skipped=${it.isSkipped}" }}" },
            { condition(viewer.diffChanges) },
            10,
        )
    }

    private fun history(old: String, new: String, fileName: String) = SimpleDiffRequest(
        fileName,
        DiffContents.vcsRevision(project, old, "/repo/$fileName"),
        DiffContents.vcsRevision(project, new, "/repo/$fileName"),
        "abc123",
        "def456",
    )

    /** The outline's rows as shown, indented by depth, with every row expanded. */
    private fun rows(outline: OutlineController): List<String> {
        val rows = mutableListOf<String>()
        fun visit(node: DefaultMutableTreeNode, depth: Int) {
            for (child in node.children()) {
                child as DefaultMutableTreeNode
                rows += "  ".repeat(depth) + (child.userObject as OutlineItem).label
                visit(child, depth + 1)
            }
        }
        visit(outline.panel.tree.model.root as DefaultMutableTreeNode, 0)
        return rows
    }

    private fun select(outline: OutlineController, label: String) {
        val root = outline.panel.tree.model.root as DefaultMutableTreeNode
        val node = root.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }
            .single { (it.userObject as? OutlineItem)?.label == label }
        outline.panel.tree.selectionPath = TreePath(node.path)
    }

    private fun assertHighlightedAt(editor: Editor, offset: Int) {
        assertTrue("offset $offset not found in the text", offset >= 0)
        val starts = editor.markupModel.allHighlighters.map { it.startOffset }
        assertTrue("no highlighter starts at $offset; highlighters start at $starts", offset in starts)
    }

    private fun factory(engine: ComparisonEngine) = object : ComparisonEngineFactory {
        override fun create(project: Project?): ComparisonEngine = engine
    }

    /** Supports Java only, so a Kotlin diff has no engine when this factory masks the real ones. */
    private class JavaOnlyEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.javaOnly")
        override val displayName = "Java-only (test)"
        override val version = "1"

        override fun supports(languageId: String) = languageId == "JAVA"

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult =
            error("the outline must not call an engine for a language it does not support")
    }

    /** Never finishes; it checks for cancellation until it is canceled. */
    private class SlowEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.slow")
        override val displayName = "Slow (test)"
        override val version = "1"

        @Volatile
        var started = false

        @Volatile
        var canceled = false

        override fun supports(languageId: String) = true

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
            started = true
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
        const val JAVA_OLD = "class Greeter {\n    String hello() { return \"hi\"; }\n}\n"
        const val JAVA_NEW = "class Greeter {\n    String hello() { return \"hello\"; }\n\n    void bye() {}\n}\n"
        const val FORMATTED_OLD = "class Counter {\n    int a() { return 1; }\n\n    int b() { return 2; }\n}\n"
        const val FORMATTED_NEW = "class Counter {\n    int a() { return 3; }\n\n    int b() {\n        return 2;\n    }\n}\n"
        const val KOTLIN_OLD = "class Greeter {\n    fun hello() = \"hi\"\n}\n"
        const val KOTLIN_NEW = "class Greeter {\n    fun hello() = \"hello\"\n\n    fun bye() {}\n}\n"
    }
}
