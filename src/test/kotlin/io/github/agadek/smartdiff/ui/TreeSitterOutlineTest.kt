package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.lines.LineEngine

/** The outline of a Swift diff in an IDE without Swift support (engines/05 R1, R2, R5, R7). */
class TreeSitterOutlineTest : BasePlatformTestCase() {
    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null
    private val created = mutableListOf<Pair<DiffRequest, FrameDiffTool.DiffViewer>>()

    private val old = "final class Cart {\n    func total(prices: [Int]) -> Int {\n        return prices.reduce(0, +)\n    }\n}\n\n" +
        "struct Note {\n    let text = \"The first paragraph has a long line of text here.\"\n    let more = \"It continues with another line of text.\"\n    let end = \"A third line finishes the paragraph.\"\n}\n"
    private val new = "struct Note {\n    let text = \"The first paragraph has a long line of text here.\"\n    let more = \"It continues with another line of text.\"\n    let end = \"A third line finishes the paragraph.\"\n}\n\n" +
        "final class Cart {\n    func total(prices: [Int]) -> Int {\n        return prices.reduce(0, +) * 2\n    }\n}\n"

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

    fun `test engines 05 R2 R5 R7 a Swift file opened as plain text gets the Tree-sitter outline`() {
        // engines/05-tree-sitter R2, R5, R7
        val outline = awaitResult(open())
        val result = resultOf(outline)
        assertEquals(EngineId("smartdiff.treesitter"), result.engineId)
        assertEquals(Status.ANALYZED, result.status)
        assertTrue(result.changes.any { it.isReported })
        assertTrue(outline.panel.summary.text, outline.panel.summary.text.endsWith("· tree-sitter"))
    }

    fun `test engines 05 R1 with the setting off a Swift file is compared as without Tree-sitter`() {
        // engines/05-tree-sitter R1, settings/01 R25
        assertTrue("the setting starts on", SmartDiffSettings.SettingsState().treeSitterLanguages)
        settings.treeSitterLanguages = false
        val outline = open()
        // The line comparison answers either with moved blocks or with its no-moved-lines message (engines/04 R14).
        PlatformTestUtil.waitWithEventsDispatching(
            { "the outline shows ${outline.state}" },
            { outline.state is OutlineState.NoMovedLines || (outline.state as? OutlineState.Shown)?.result?.engineId == LineEngine.ID },
            20,
        )

        settings.compareLinesWithoutEngine = false
        val viewer = openViewer()
        assertNull("no outline with both settings off, as for plain text", OutlineController.of(viewer))
    }

    private fun request() = SimpleDiffRequest(
        "Cart.swift",
        DiffContents.vcsRevision(project, old, "/repo/Cart.swift"),
        DiffContents.vcsRevision(project, new, "/repo/Cart.swift"),
        "old", "new",
    )

    private fun openViewer(): FrameDiffTool.DiffViewer {
        val request = request()
        val panel = DiffManager.getInstance().createRequestPanel(project, Disposer.newDisposable(testRootDisposable), null)
        panel.setRequest(request)
        waitFor { created.any { it.first === request } }
        return created.last { it.first === request }.second
    }

    private fun open(): OutlineController {
        val viewer = openViewer() as TwosideTextDiffViewer
        return OutlineController.of(viewer) ?: error("no outline attached")
    }

    private fun awaitResult(outline: OutlineController): OutlineController {
        PlatformTestUtil.waitWithEventsDispatching({ "no result; the outline shows ${outline.state}" }, { outline.state is OutlineState.Shown }, 20)
        return outline
    }

    private fun resultOf(outline: OutlineController): ComparisonResult = (outline.state as OutlineState.Shown).result

    private fun waitFor(condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching("condition not met", condition, 20)
}
