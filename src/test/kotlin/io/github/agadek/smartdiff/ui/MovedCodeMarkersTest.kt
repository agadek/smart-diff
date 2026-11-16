package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.Disposable
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
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipEndpoint
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory

/** The moved-code markers of the shown outline rows (outline/13 R1 to R6). */
class MovedCodeMarkersTest : BasePlatformTestCase() {

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
        ExtensionTestUtil.maskExtensions(
            ComparisonEngineFactory.EP_NAME,
            listOf(object : ComparisonEngineFactory {
                override fun create(p: com.intellij.openapi.project.Project?) = MarkerEngine()
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

    fun `test outline 13 R1 the setting is on by default and off adds no marker`() {
        // outline/13 R1
        assertTrue("marking moved code starts on", SmartDiffSettings.SettingsState().markMovedCode)
        settings.loadState(SmartDiffSettings.SettingsState())

        val on = open(history()).awaitResult()
        assertEquals("a marker per moved row on each side", 8, on.outline.movedMarkers.shown.size)

        settings.markMovedCode = false

        val off = open(history()).awaitResult()
        assertTrue("no markers when the setting is off", off.outline.movedMarkers.shown.isEmpty())
        assertTrue(
            "no gutter icon in either editor",
            listOf(off.viewer.editor1, off.viewer.editor2).all { editor ->
                editor.markupModel.allHighlighters.none { it.gutterIconRenderer is MovedCodeGutterIcon }
            },
        )
    }

    fun `test outline 13 R2 a moved declaration has one marker on each side`() {
        // outline/13 R2
        val shown = open(history()).awaitResult()
        val old = shown.viewer.editor1
        val new = shown.viewer.editor2

        val oldMarkers = shown.outline.movedMarkers.shown.filter { it.document === old.document }
        val newMarkers = shown.outline.movedMarkers.shown.filter { it.document === new.document }
        assertEquals(4, oldMarkers.size)
        assertEquals(4, newMarkers.size)
        assertTrue("old markers sit at the first line of each old range", oldMarkers.all { marker ->
            MOVED_DECLARATIONS.any { rangeOf(OLD, it)?.start == marker.startOffset }
        })
        assertTrue("new markers sit at the first line of each new range", newMarkers.all { marker ->
            MOVED_DECLARATIONS.any { rangeOf(NEW, it)?.start == marker.startOffset }
        })
        assertTrue("every marker has a gutter icon", (oldMarkers + newMarkers).all { it.gutterIconRenderer != null })

        shown.outline.setOutlineView(OutlineView.REFACTORINGS)
        assertEquals("the refactorings view marks the same ranges", 8, shown.outline.movedMarkers.shown.size)
        assertEquals(
            "the move relationship rows carry the ranges",
            MOVED_DECLARATIONS.map { rangeOf(NEW, it)!!.start }.sorted(),
            shown.outline.movedMarkers.shown.filter { it.document === new.document }.map { it.startOffset }.sorted(),
        )
    }

    fun `test outline 13 R3 hidden moved rows have no marker`() {
        // outline/13 R3
        val shown = open(history()).awaitResult()
        val outline = shown.outline
        assertEquals(8, outline.movedMarkers.shown.size)

        outline.setHidden(ChangeType.MOVED, true)
        assertTrue("the change type filter removes the markers", outline.movedMarkers.shown.isEmpty())

        outline.setHidden(ChangeType.MOVED, false)
        assertEquals(8, outline.movedMarkers.shown.size)

        outline.setOutlineView(OutlineView.REFACTORINGS)
        assertEquals("the refactorings view marks the relationship rows", 8, outline.movedMarkers.shown.size)

        outline.setHiddenRelationshipKind(RelationshipKind.MOVE, true)
        assertTrue("the relationship kind filter removes the markers", outline.movedMarkers.shown.isEmpty())
    }

    fun `test outline 13 R4 the icon is the same on both sides and adds no colour`() {
        // outline/13 R4
        val shown = open(history()).awaitResult()
        val markers = shown.outline.movedMarkers.shown

        assertEquals("one icon for all markers", 1, markers.map { it.gutterIconRenderer!!.icon }.toSet().size)
        assertTrue("no text attributes replace the diff's colours", markers.all { it.getTextAttributes(null) == null })
        assertTrue(
            "all markers are line marks",
            markers.all { it.startOffset < it.endOffset && it.document.getLineNumber(it.startOffset) <= it.document.getLineNumber(it.endOffset - 1) },
        )
    }

    fun `test outline 13 R5 the tooltip names the other side's line`() {
        // outline/13 R5
        val shown = open(history()).awaitResult()
        val tooltips = shown.outline.movedMarkers.shown.map { it.gutterIconRenderer!!.tooltipText }

        assertTrue("new side: ${tooltips}", "applyDiscount: Moved from line 42" in tooltips)
        assertTrue("old side: ${tooltips}", "applyDiscount: Moved to line 17" in tooltips)
        assertTrue(
            "the scrollbar names the same line as the icon",
            shown.outline.movedMarkers.shown.all { it.errorStripeTooltip == it.gutterIconRenderer!!.tooltipText },
        )
    }

    fun `test outline 13 R6 each marker has a thin scrollbar mark`() {
        // outline/13 R6
        val shown = open(history()).awaitResult()
        val markers = shown.outline.movedMarkers.shown

        assertEquals(8, markers.size)
        assertTrue("one colour for every mark", markers.all { it.getErrorStripeMarkColor(null) == MovedCodeMarkers.MOVED_MARK })
        assertTrue("the marks are thin", markers.all { it.isThinErrorStripeMark })
    }

    private inner class Shown(val panel: DiffRequestPanel, val viewer: TwosideTextDiffViewer) {
        val outline: OutlineController = OutlineController.of(viewer) ?: error("no outline attached")
        fun awaitResult(): Shown = apply { PlatformTestUtil.waitWithEventsDispatching("no result", { outline.state is OutlineState.Shown }, 10) }
    }

    private fun open(request: SimpleDiffRequest): Shown {
        val disposable: Disposable = Disposer.newDisposable(testRootDisposable)
        val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        panel.setRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("no viewer", { created.any { it.first === request } }, 10)
        return Shown(panel, created.last { it.first === request }.second as TwosideTextDiffViewer)
    }

    private fun history(): SimpleDiffRequest = SimpleDiffRequest(
        "Fixture.java",
        DiffContents.vcsRevision(project, OLD, "/repo/Fixture.java"),
        DiffContents.vcsRevision(project, NEW, "/repo/Fixture.java"),
        "old",
        "new",
    )

    /** Builds the moved result from the documents, so an edit before the next comparison shifts the ranges. */
    private class MarkerEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.movedMarkers")
        override val displayName = "Moved markers (test)"
        override val version = "1"
        override fun supports(languageId: String) = languageId == "JAVA"

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
            val nodes = MOVED_DECLARATIONS.map { marker ->
                val subject = if (marker == DISCOUNT) Subject.Declaration(DeclarationKind.ITEM, "applyDiscount", null)
                else Subject.Declaration(DeclarationKind.METHOD, marker.substringAfter("int ").substringBefore("()"), "")
                ChangeNode(
                    subject,
                    rangeOf(request.oldText, marker),
                    rangeOf(request.newText, marker),
                    Presence.MATCHED,
                    moved = true,
                    content = Content.UNCHANGED,
                )
            }
            return ComparisonResult(
                id, version, Status.ANALYZED,
                changes = nodes,
                relationships = nodes.map { node ->
                    Relationship(
                        setOf(RelationshipKind.MOVE),
                        listOf(RelationshipEndpoint(node.subject, node.oldRange!!)),
                        listOf(RelationshipEndpoint(node.subject, node.newRange!!)),
                        Confidence.HIGH,
                        emptyList(),
                    )
                },
            )
        }
    }

    companion object {
        const val ALPHA = "int alpha()"
        const val DISCOUNT = "int applyDiscount()"
        const val BETA = "int beta()"
        const val INNER = "class Inner"
        val MOVED_DECLARATIONS = listOf(ALPHA, DISCOUNT, BETA, INNER)

        private fun java(declarations: List<Pair<Int, String>>, lastLine: Int): String = buildString {
            append("class Fixture {\n")
            for (line in 2..lastLine) {
                val declaration = declarations.firstOrNull { it.first == line }?.second
                append("    ${declaration ?: "int filler$line() { return $line; }"}\n")
            }
            append("}\n")
        }

        val OLD = java(listOf(8 to ALPHA, 42 to DISCOUNT, 25 to BETA, 33 to INNER), 50)
        val NEW = java(listOf(9 to INNER, 20 to BETA, 17 to DISCOUNT, 50 to ALPHA), 60)

        fun rangeOf(text: CharSequence, declaration: String): TextRange? {
            val start = text.indexOf(declaration).takeIf { it >= 0 } ?: return null
            return TextRange(start, start + declaration.length)
        }
    }
}
