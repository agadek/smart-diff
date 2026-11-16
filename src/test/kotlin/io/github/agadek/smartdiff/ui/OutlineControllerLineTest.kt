package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.lines.LineEngine
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** The outline of a two-sided diff compared by lines (engines/04). */
class OutlineControllerLineTest : BasePlatformTestCase() {

    private val header = "# Notes for reviewers\n"
    private val paragraph = "The first paragraph has a long line of text here.\nIt continues with another line of text.\nA third line finishes the paragraph.\nA fourth line ends the paragraph here.\n"
    private val other = "The other paragraph has a long line of text here.\nIt continues with a different line of text.\nA third line finishes the other paragraph.\nA fourth line ends the other paragraph here.\n"

    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null
    private val created = mutableListOf<Pair<DiffRequest, FrameDiffTool.DiffViewer>>()

    override fun setUp() {
        super.setUp()
        saved = settings.state
        settings.loadState(SmartDiffSettings.SettingsState())
        val extension = object : DiffExtension() {
            override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
                created += request to viewer
            }
        }
        ExtensionTestUtil.addExtensions(DiffExtension.EP_NAME, listOf(extension), testRootDisposable)
    }

    override fun tearDown() {
        try {
            saved?.let(settings::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test engines 04 R1 R3 a plain-text diff gets the line outline when the setting is on and no panel when off`() {
        // engines/04-moved-lines R1, R3
        val shown = open(movedDiff())
        val outline = shown.awaitResult()
        assertEquals(LineEngine.ID, resultOf(outline).engineId)
        assertTrue(UIUtil.isAncestor(shown.viewer.component, outline.panel))

        settings.compareLinesWithoutEngine = false
        val without = openAny(movedDiff())
        assertNull("no outline with the setting off", OutlineController.of(without))
    }

    fun `test engines 04 R4 a language with an engine uses it even when it reports a fallback state`() {
        // engines/04-moved-lines R4
        maskEngines(UnsupportedEngine())
        val outline = open(javaDiff()).awaitResult()

        assertEquals(EngineId("smartdiff.test.unsupported"), resultOf(outline).engineId)
        assertEquals(Status.UNSUPPORTED, resultOf(outline).status)
    }

    fun `test engines 04 R19 one-sided and three-sided diffs do not use the line comparison`() {
        // engines/04-moved-lines R19
        val oneSided = SimpleDiffRequest("one.txt", DiffContentFactory.getInstance().createEmpty(), DiffContents.vcsRevision(project, paragraph, "/repo/one.txt"), "old", "new")
        val oneViewer = openAny(oneSided)
        assertNull("one-sided plain text gets no panel", OutlineController.of(oneViewer))

        val threeSided = SimpleDiffRequest(
            "three.txt",
            listOf(
                DiffContents.vcsRevision(project, paragraph, "/repo/three.txt"),
                DiffContents.vcsRevision(project, header + paragraph, "/repo/three.txt"),
                DiffContents.vcsRevision(project, header + other, "/repo/three.txt"),
            ),
            listOf("base", "left", "right"),
        )
        val threeViewer = openAny(threeSided)
        assertNull("three-sided plain text gets no merge outline", OutlineController.of(threeViewer))
    }

    fun `test engines 04 R13 the summary line ends with lines`() {
        // engines/04-moved-lines R13
        assertTrue(open(movedDiff()).awaitResult().panel.summary.text.endsWith("· lines"))
    }

    fun `test engines 04 R14 a comparison with no moved block shows the message`() {
        // engines/04-moved-lines R14
        val outline = open(simpleDiff("one line here\n", "a changed line here\n")).outline
        waitFor { outline.state is OutlineState.NoMovedLines }

        assertEquals(OutlineState.NoMovedLines, outline.state)
        assertEquals("No moved lines found. The native diff shows every change.", outline.panel.message.accessibleContext.accessibleName)
        assertTrue((outline.panel.tree.model.root as DefaultMutableTreeNode).childCount == 0)
    }

    fun `test engines 04 R11 selecting a moved block frames both sides`() {
        // engines/04-moved-lines R11
        val shown = open(movedDiff())
        val outline = shown.awaitResult()
        val row = firstRow(outline)

        outline.panel.tree.selectionPath = TreePath(row.path)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals("a range is framed on both sides", 2, outline.navigator.shown.count { it.customRenderer != null && it.getErrorStripeMarkColor(null) != null })
    }

    fun `test engines 04 R15 moved-code markers appear in both editors for each block`() {
        // engines/04-moved-lines R15
        val shown = open(movedDiff())
        val outline = shown.awaitResult()

        assertTrue("markers were shown", outline.movedMarkers.shown.isNotEmpty())
        assertTrue("the old editor has a marker", outline.movedMarkers.shown.any { it.document === shown.viewer.editor1.document })
        assertTrue("the new editor has a marker", outline.movedMarkers.shown.any { it.document === shown.viewer.editor2.document })
    }

    fun `test engines 04 R16 hiding moved hides the block rows`() {
        // engines/04-moved-lines R16
        val outline = open(movedDiff()).awaitResult()
        assertTrue("the block is shown", rows(outline).any { it.contains("lines ") })

        outline.setHidden(ChangeType.MOVED, true)

        assertFalse("the block is hidden", rows(outline).any { it.contains("lines ") })
    }

    fun `test engines 04 R17 the engine dropdown is hidden for a line comparison`() {
        // engines/04-moved-lines R17
        val outline = open(movedDiff()).awaitResult()

        assertFalse(outline.panel.engines.isVisible)
        assertFalse("no default engine is offered", SmartDiffConfigurable.languagesWithEngines(
            io.github.agadek.smartdiff.engine.registry.EngineRegistry.getInstance(project).engines(),
        ).any { (language, _) -> language.id == "TEXT" })
    }

    fun `test engines 04 R18 a file over the size limit shows Too large to analyze`() {
        // engines/04-moved-lines R18
        settings.limits = settings.limits.copy(maxChars = 10)
        val outline = open(movedDiff()).awaitResult { it.result.status == Status.TOO_LARGE }

        assertEquals(Status.TOO_LARGE, resultOf(outline).status)
        assertTrue(outline.panel.message.text.contains("Too large to analyze"))
    }

    fun `test engines 04 R21 applying a setting change refreshes an open outline`() {
        // engines/04-moved-lines R21
        val outline = open(movedDiff()).awaitResult()
        assertEquals(LineEngine.ID, resultOf(outline).engineId)

        settings.compareLinesWithoutEngine = false
        ApplicationManager.getApplication().messageBus.syncPublisher(SmartDiffSettings.CHANGED).settingsChanged()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue("the old line result was dropped", outline.state is OutlineState.NoEngine)
    }

    private fun movedDiff() = simpleDiff(header + paragraph + other, header + other + paragraph)

    private fun simpleDiff(old: String, new: String) = SimpleDiffRequest(
        "notes.txt",
        DiffContents.vcsRevision(project, old, "/repo/notes.txt"),
        DiffContents.vcsRevision(project, new, "/repo/notes.txt"),
        "old", "new",
    )

    private fun javaDiff() = SimpleDiffRequest(
        "Greeter.java",
        DiffContents.vcsRevision(project, "class Greeter {}\n", "/repo/Greeter.java"),
        DiffContents.vcsRevision(project, "class Greeter { int x; }\n", "/repo/Greeter.java"),
        "old", "new",
    )

    private inner class Shown(val viewer: TwosideTextDiffViewer) {
        val outline: OutlineController = OutlineController.of(viewer) ?: error("no outline attached")

        fun awaitResult(condition: (OutlineState.Shown) -> Boolean = { true }): OutlineController {
            waitFor { (outline.state as? OutlineState.Shown)?.let(condition) == true }
            return outline
        }
    }

    private fun open(request: SimpleDiffRequest): Shown {
        val panel = DiffManager.getInstance().createRequestPanel(project, Disposer.newDisposable(testRootDisposable), null)
        panel.setRequest(request)
        return Shown(awaitViewer(request) as TwosideTextDiffViewer)
    }

    private fun openAny(request: SimpleDiffRequest): FrameDiffTool.DiffViewer {
        val panel = DiffManager.getInstance().createRequestPanel(project, Disposer.newDisposable(testRootDisposable), null)
        panel.setRequest(request)
        return awaitViewer(request)
    }

    private fun awaitViewer(request: DiffRequest): FrameDiffTool.DiffViewer {
        waitFor { created.any { it.first === request } }
        return created.last { it.first === request }.second
    }

    private fun waitFor(condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching("condition not met; state is ${created.lastOrNull()?.second?.let(OutlineController::of)?.state}", condition, 10)

    private fun maskEngines(vararg engines: ComparisonEngine) {
        val factories = engines.map { engine -> object : ComparisonEngineFactory {
            override fun create(project: Project?): ComparisonEngine = engine
        } }
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, factories, testRootDisposable)
    }

    private class UnsupportedEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.unsupported")
        override val displayName = "Unsupported (test)"
        override val version = "1"
        override fun supports(languageId: String) = languageId == "JAVA"
        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult =
            ComparisonResult.unsupported(this, "test fallback")
    }

    private fun resultOf(outline: OutlineController) = (outline.state as OutlineState.Shown).result

    private fun firstRow(outline: OutlineController): DefaultMutableTreeNode =
        (outline.panel.tree.model.root as DefaultMutableTreeNode).firstChild as DefaultMutableTreeNode

    private fun rows(outline: OutlineController): List<String> {
        val result = mutableListOf<String>()
        collectRows(outline.panel.tree.model.root as DefaultMutableTreeNode, result)
        return result
    }

    private fun collectRows(node: DefaultMutableTreeNode, into: MutableList<String>) {
        for (child in node.children()) {
            child as DefaultMutableTreeNode
            into += (child.userObject as? OutlineItem)?.label.orEmpty()
            collectRows(child, into)
        }
    }
}
