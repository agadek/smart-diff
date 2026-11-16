package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder.TextDiffSettings
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * Expand Declaration (outline/14): the tests open a real `SimpleDiffViewer` with the platform's collapsed unchanged
 * fragments and drive the outline and editor actions. TextDiffSettings keeps collapsing on with a small context range,
 * so the platform builds the fragments itself; the original settings are restored after each test.
 */
class ExpandDeclarationTest : BasePlatformTestCase() {

    private val created = mutableListOf<Pair<DiffRequest, FrameDiffTool.DiffViewer>>()
    private val settings get() = SmartDiffSettings.getInstance()
    private var savedSettings: SmartDiffSettings.SettingsState? = null
    private var savedExpandByDefault = true
    private var savedContextRange = 4

    override fun setUp() {
        super.setUp()
        savedSettings = settings.state
        val text = textSettings()
        savedExpandByDefault = text.isExpandByDefault
        savedContextRange = text.contextRange
        text.isExpandByDefault = false
        text.contextRange = 1
        val extension = object : DiffExtension() {
            override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
                created += request to viewer
            }
        }
        ExtensionTestUtil.addExtensions(DiffExtension.EP_NAME, listOf(extension), testRootDisposable)
    }

    override fun tearDown() {
        try {
            textSettings().isExpandByDefault = savedExpandByDefault
            textSettings().contextRange = savedContextRange
            savedSettings?.let(settings::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test outline 14 R1 R3 R6 the row action expands only fragments in the declaration`() {
        // outline/14-expand-to-declaration R1, R3, R6
        val (old, new) = fragmentsFixture(sigNew = "int target(int p, int q)", midNew = 2, retNew = "return p + q; }")
        val shown = open(history(old, new, "Fragments.java"))
        val outline = shown.awaitResult()
        val item = selectItem(outline) { it.title.contains("target") }
        val target = lineRange(new, "int target(")..lineRange(new, "return p + q; }")

        val before = collapsedRanges(shown.viewer.editor2)
        val inside = before.filter { it.first <= target.last && target.first <= it.last }
        val outside = before.filter { it !in inside }
        assertTrue("the declaration has collapsed fragments inside it: $before", inside.isNotEmpty())
        assertTrue("other methods have collapsed fragments: $before", outside.isNotEmpty())

        val event = TestActionEvent.createTestEvent(outline.panel.expandDeclarationAction)
        outline.panel.expandDeclarationAction.update(event)
        assertTrue("the row action is visible on a change row with fragments", event.presentation.isEnabledAndVisible)
        outline.panel.expandDeclarationAction.actionPerformed(event)

        val after = collapsedRanges(shown.viewer.editor2)
        assertTrue("no collapsed fragment overlaps the declaration: $after", after.none { it.first <= target.last && target.first <= it.last })
        assertEquals("fragments outside the declaration stay collapsed", outside.toSet(), after.toSet())
    }

    fun `test outline 14 R2 the editor action uses the row at the caret`() {
        // outline/14-expand-to-declaration R2
        val (old, new) = twoClassFixture()
        val shown = open(history(old, new, "Two.java"))
        val outline = shown.awaitResult()
        val editor = shown.viewer.editor2
        val action = ActionManager.getInstance().getAction("SmartDiff.ExpandDeclaration")!!

        editor.caretModel.moveToOffset(new.indexOf("int mid = 2") + 4)
        val onChange = editorEvent(action, editor)
        action.update(onChange)
        assertTrue("the editor action is visible with the caret on the changed line", onChange.presentation.isEnabledAndVisible)
        action.actionPerformed(onChange)
        val target = lineRange(new, "int target(")..lineRange(new, "return p; }")
        assertTrue(
            "the declaration's fragments expanded: ${collapsedRanges(editor)}",
            collapsedRanges(editor).none { it.first <= target.last && target.first <= it.last },
        )

        editor.caretModel.moveToOffset(new.indexOf("void stay()") + 4)
        val onUnchanged = editorEvent(action, editor)
        action.update(onUnchanged)
        assertFalse("the editor action is hidden with the caret on an unchanged method", onUnchanged.presentation.isEnabledAndVisible)
    }

    fun `test outline 14 R4 both sides expand`() {
        // outline/14-expand-to-declaration R4
        val (old, new) = fragmentsFixture(sigNew = "int target(int p, int q)", midNew = 2, retNew = "return p + q; }")
        val shown = open(history(old, new, "Fragments.java"))
        val outline = shown.awaitResult()
        selectItem(outline) { it.title.contains("target") }

        val oldTarget = lineRange(old, "int target(")..lineRange(old, "return p; }")
        val newTarget = lineRange(new, "int target(")..lineRange(new, "return p + q; }")
        val oldInside = collapsedRanges(shown.viewer.editor1).filter { it.first <= oldTarget.last && oldTarget.first <= it.last }
        val newInside = collapsedRanges(shown.viewer.editor2).filter { it.first <= newTarget.last && newTarget.first <= it.last }
        assertTrue("old side has fragments in the declaration", oldInside.isNotEmpty())
        assertTrue("new side has fragments in the declaration", newInside.isNotEmpty())

        outline.expandDeclaration(outline.panel.selectedItem()!!)

        assertTrue(
            "old side expanded: ${collapsedRanges(shown.viewer.editor1)}",
            collapsedRanges(shown.viewer.editor1).none { it in oldInside },
        )
        assertTrue(
            "new side expanded: ${collapsedRanges(shown.viewer.editor2)}",
            collapsedRanges(shown.viewer.editor2).none { it in newInside },
        )
    }

    fun `test outline 14 R5 a fragment that crosses the declaration expands whole`() {
        // outline/14-expand-to-declaration R5
        val (old, new) = fragmentsFixture(sigNew = "int target(int p)", midNew = 2, retNew = "return p; }")
        val shown = open(history(old, new, "Fragments.java"))
        val outline = shown.awaitResult()
        selectItem(outline) { it.title.contains("target") }

        val editor = shown.viewer.editor2
        val target = lineRange(new, "int target(")..lineRange(new, "return p; }")
        val afterStart = lineRange(new, "int after()")
        val crossing = UnchangedFragments.collapsed(editor).filter { region ->
            val first = editor.document.getLineNumber(region.startOffset)
            val last = editor.document.getLineNumber(region.endOffset)
            first in target && last >= afterStart
        }
        assertTrue("a collapsed fragment crosses from the declaration into the next method", crossing.isNotEmpty())

        outline.expandDeclaration(outline.panel.selectedItem()!!)

        assertTrue("the crossing fragment expanded in full", crossing.all { it.isExpanded })
        assertTrue("the following method is now visible", collapsedRanges(editor).none { it.first >= afterStart && it.first <= afterStart + 5 })
    }

    fun `test outline 14 R7 a relationship row expands each declaration it frames`() {
        // outline/14-expand-to-declaration R7
        val (old, new) = renameFixture()
        val shown = open(history(old, new, "Rename.java"))
        val outline = shown.awaitResult()
        outline.setOutlineView(OutlineView.REFACTORINGS)
        val relationship = awaitItem(outline) { it.kind == io.github.agadek.smartdiff.ui.ItemKind.RELATIONSHIP }
        selectItem(outline) { it === relationship }

        val oldTarget = lineRange(old, "int total(")..lineRange(old, "return p; }")
        val newTarget = lineRange(new, "int sum(")..lineRange(new, "return p; }")
        val oldInside = collapsedRanges(shown.viewer.editor1).filter { it.first <= oldTarget.last && oldTarget.first <= it.last }
        val newInside = collapsedRanges(shown.viewer.editor2).filter { it.first <= newTarget.last && newTarget.first <= it.last }
        assertTrue("the old declaration has collapsed fragments", oldInside.isNotEmpty())
        assertTrue("the new declaration has collapsed fragments", newInside.isNotEmpty())

        val event = TestActionEvent.createTestEvent(outline.panel.expandDeclarationAction)
        outline.panel.expandDeclarationAction.update(event)
        assertTrue("the relationship row has the action", event.presentation.isEnabledAndVisible)
        outline.expandDeclaration(relationship)

        assertTrue("fragments at the old end expanded", collapsedRanges(shown.viewer.editor1).none { it in oldInside })
        assertTrue("fragments at the new end expanded", collapsedRanges(shown.viewer.editor2).none { it in newInside })
    }

    fun `test outline 14 R8 hidden for groups the file region and rows with nothing to expand`() {
        // outline/14-expand-to-declaration R8
        val (formattedOld, formattedNew) = formattedFixture()
        val formatted = open(history(formattedOld, formattedNew, "Formatted.java")).awaitResult()
        val group = awaitItem(formatted) { it.isGroup }
        selectItem(formatted) { it === group }
        assertFalse("a group row has no Expand Declaration", visible(formatted.panel.expandDeclarationAction))

        val (regionOld, regionNew) = fileRegionFixture()
        val region = open(history(regionOld, regionNew, "Region.java")).awaitResult()
        val fileRegion = awaitItem(region) { it.kind == io.github.agadek.smartdiff.ui.ItemKind.FILE_REGION }
        selectItem(region) { it === fileRegion }
        assertFalse("the file region row has no Expand Declaration", visible(region.panel.expandDeclarationAction))

        val (old, new) = fragmentsFixture(sigNew = "int target(int p, int q)", midNew = 2, retNew = "return p + q; }")
        val shown = open(history(old, new, "Fragments.java"))
        val outline = shown.awaitResult()
        val item = selectItem(outline) { it.title.contains("target") }
        assertTrue("visible while fragments are collapsed", visible(outline.panel.expandDeclarationAction))
        outline.expandDeclaration(item)
        assertFalse("hidden once the row's fragments are expanded", visible(outline.panel.expandDeclarationAction))

        textSettings().isExpandByDefault = true
        val expanded = open(history(old, new, "Expanded.java")).awaitResult()
        selectItem(expanded) { it.title.contains("target") }
        assertFalse("hidden when collapsing is off", visible(expanded.panel.expandDeclarationAction))
    }

    fun `test outline 14 R9 the declaration's first line is shown and the selection kept`() {
        // outline/14-expand-to-declaration R9
        val (old, new) = fragmentsFixture(sigNew = "int target(int p, int q)", midNew = 2, retNew = "return p + q; }")
        val shown = open(history(old, new, "Fragments.java"))
        val outline = shown.awaitResult()
        val item = selectItem(outline) { it.title.contains("target") }
        showEditor(shown.viewer.editor1)
        showEditor(shown.viewer.editor2)
        shown.viewer.editor1.scrollingModel.scrollVertically(4000)
        shown.viewer.editor2.scrollingModel.scrollVertically(4000)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        outline.expandDeclaration(item)

        assertFirstLineVisible(shown.viewer.editor1, lineRange(old, "int target("))
        assertFirstLineVisible(shown.viewer.editor2, lineRange(new, "int target("))
        assertSame("the outline keeps its selection", item, outline.panel.selectedItem())
    }

    fun `test outline 14 R10 expanding does not compare again`() {
        // outline/14-expand-to-declaration R10
        val real = requireNotNull(EngineRegistry.getInstance(project).select("JAVA").selected)
        var calls = 0
        val counting = object : ComparisonEngine by real {
            override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
                calls++
                return real.compare(request, cancellation)
            }
        }
        maskEngines(counting)
        val (old, new) = fragmentsFixture(sigNew = "int target(int p, int q)", midNew = 2, retNew = "return p + q; }")
        val shown = open(history(old, new, "Fragments.java"))
        val outline = shown.awaitResult()
        val item = selectItem(outline) { it.title.contains("target") }
        val result = (outline.state as OutlineState.Shown).result
        val model = outline.panel.tree.model
        val expand = textSettings().isExpandByDefault
        val before = calls
        assertTrue("the comparison ran before the action", before > 0)

        outline.expandDeclaration(item)

        assertEquals("no new comparison", before, calls)
        assertSame("no new result", result, (outline.state as OutlineState.Shown).result)
        assertSame("no model rebuild", model, outline.panel.tree.model)
        assertEquals("the collapse setting is unchanged", expand, textSettings().isExpandByDefault)
    }

    // ---- helpers ----

    private fun textSettings(): TextDiffSettings = TextDiffSettings.getSettings()

    private fun editorEvent(action: AnAction, editor: Editor) =
        TestActionEvent.createTestEvent(action, SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, editor))

    private fun visible(action: AnAction): Boolean {
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }

    private fun collapsedRanges(editor: Editor): List<IntRange> =
        UnchangedFragments.collapsed(editor).map { region ->
            editor.document.getLineNumber(region.startOffset)..editor.document.getLineNumber(region.endOffset)
        }

    private fun lineRange(text: String, marker: String): Int =
        text.substring(0, text.indexOf(marker)).count { it == '\n' }

    private fun showEditor(editor: Editor) {
        EditorTestUtil.setEditorVisibleSize(editor, 100, 14)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun assertFirstLineVisible(editor: Editor, line: Int) {
        val y = editor.logicalPositionToXY(LogicalPosition(line, 0)).y
        val area = editor.scrollingModel.visibleArea
        assertTrue("line $line (y=$y) is in the visible area $area", y in area.y until area.y + area.height)
    }

    private fun selectItem(outline: OutlineController, predicate: (OutlineItem) -> Boolean): OutlineItem {
        val root = outline.panel.tree.model.root as DefaultMutableTreeNode
        val node = root.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }
            .first { (it.userObject as? OutlineItem)?.let(predicate) == true }
        outline.panel.tree.selectionPath = TreePath(node.path)
        return node.userObject as OutlineItem
    }

    private fun awaitItem(outline: OutlineController, predicate: (OutlineItem) -> Boolean): OutlineItem {
        var found: OutlineItem? = null
        PlatformTestUtil.waitWithEventsDispatching(
            { "no matching row; rows=${outline.panel.tree.model.root}" },
            {
                val root = outline.panel.tree.model.root as DefaultMutableTreeNode
                found = root.depthFirstEnumeration().toList().mapNotNull { (it as DefaultMutableTreeNode).userObject as? OutlineItem }
                    .firstOrNull(predicate)
                found != null
            },
            10,
        )
        return found!!
    }

    private inner class Shown(val viewer: TwosideTextDiffViewer) {
        val outline: OutlineController = OutlineController.of(viewer) ?: error("no outline attached")
        fun awaitResult(): OutlineController {
            this@ExpandDeclarationTest.awaitResult(outline)
            return outline
        }
    }

    private fun open(request: SimpleDiffRequest): Shown {
        val panel = DiffManager.getInstance().createRequestPanel(project, Disposer.newDisposable(testRootDisposable), null)
        panel.setRequest(request)
        return Shown(awaitViewer(request))
    }

    private fun awaitViewer(request: DiffRequest): TwosideTextDiffViewer {
        PlatformTestUtil.waitWithEventsDispatching("no diff viewer was created", { created.any { it.first === request } }, 10)
        return created.last { it.first === request }.second as TwosideTextDiffViewer
    }

    private fun awaitResult(outline: OutlineController) {
        PlatformTestUtil.waitWithEventsDispatching(
            { "no result; state is ${outline.state}" },
            { outline.state is OutlineState.Shown },
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

    private fun factory(engine: ComparisonEngine) = object : ComparisonEngineFactory {
        override fun create(project: Project?): ComparisonEngine = engine
    }

    private fun maskEngines(vararg engines: ComparisonEngine) {
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, engines.map(::factory), testRootDisposable)
    }

    /** Three methods in one class. [sigNew], [midNew] and [retNew] are the changed lines; the other methods stay long and unchanged. */
    private fun fragmentsFixture(sigNew: String, midNew: Int, retNew: String): Pair<String, String> {
        fun file(sig: String, mid: Int, ret: String) = buildString {
            append("class Fragments {\n")
            append("    int before() {\n")
            repeat(20) { append("        int a$it = $it;\n") }
            append("        return 0;\n")
            append("    }\n\n")
            append("    $sig {\n")
            repeat(20) { append("        int b$it = $it;\n") }
            append("        int mid = $mid;\n")
            repeat(20) { append("        int c$it = $it;\n") }
            append("        $ret\n")
            append("\n")
            append("    int after() {\n")
            repeat(20) { append("        int d$it = $it;\n") }
            append("        return 0;\n")
            append("    }\n")
            append("}\n")
        }
        return file("int target(int p)", 1, "return p; }") to file(sigNew, midNew, retNew)
    }

    /** A changed class with a long target and a separate, unchanged class for the caret's "hidden" case. */
    private fun twoClassFixture(): Pair<String, String> {
        fun file(mid: Int) = buildString {
            append("class Changed {\n")
            append("    int before() {\n")
            repeat(20) { append("        int a$it = $it;\n") }
            append("        return 0;\n")
            append("    }\n\n")
            append("    int target(int p) {\n")
            repeat(20) { append("        int b$it = $it;\n") }
            append("        int mid = $mid;\n")
            repeat(20) { append("        int c$it = $it;\n") }
            append("        return p; }\n")
            append("\n")
            append("    int after() {\n")
            repeat(20) { append("        int d$it = $it;\n") }
            append("        return 0;\n")
            append("    }\n")
            append("}\n\n")
            append("class Other {\n")
            append("    void stay() {\n")
            repeat(20) { append("        int s$it = $it;\n") }
            append("    }\n")
            append("}\n")
        }
        return file(1) to file(2)
    }

    /** A long method renamed across two long, unchanged methods, so a relationship row appears in the refactorings view. */
    private fun renameFixture(): Pair<String, String> {
        fun file(name: String, mid: Int) = buildString {
            append("class Rename {\n")
            append("    int before() {\n")
            repeat(20) { append("        int a$it = $it;\n") }
            append("        return 0;\n")
            append("    }\n\n")
            append("    int $name(int p) {\n")
            repeat(20) { append("        int b$it = $it;\n") }
            append("        int mid = $mid;\n")
            repeat(20) { append("        int c$it = $it;\n") }
            append("        return p; }\n")
            append("\n")
            append("    int after() {\n")
            repeat(20) { append("        int d$it = $it;\n") }
            append("        return 0;\n")
            append("    }\n")
            append("}\n")
        }
        return file("total", 1) to file("sum", 2)
    }

    /** A reformatted method, which the outline puts in a formatting-only group. */
    private fun formattedFixture(): Pair<String, String> {
        val old = "class Format {\n    int a() { return 1; }\n\n    int b() { return 2; }\n}\n"
        val new = "class Format {\n    int a() { return 1; }\n\n    int b() {\n        return 2;\n    }\n}\n"
        return old to new
    }

    /** An import change gives a file region row; a method change gives fragments to hide. */
    private fun fileRegionFixture(): Pair<String, String> {
        val old = "import java.util.List;\nclass Region {\n    int target(int p) {\n        return p;\n    }\n}\n"
        val new = "import java.util.ArrayList;\nclass Region {\n    int target(int p) {\n        return p + 1;\n    }\n}\n"
        return old to new
    }
}
