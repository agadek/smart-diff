package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.merge.MergeContext
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.merge.MergeTool
import com.intellij.diff.merge.TextMergeRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.ThreesideTextDiffViewer
import com.intellij.diff.tools.util.text.LineOffsetsUtil
import com.intellij.diff.util.MergeConflictType
import com.intellij.diff.util.MergeRangeUtil
import com.intellij.diff.util.ThreeSide
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.agadek.smartdiff.ui.DiffContents
import javax.swing.JComponent

/**
 * Spike S6 (technical specification §13, three-way merge plan phase 0): where the merge outline can attach, and what the
 * merge window gives it. The choice is recorded in ADR 0007.
 *
 * The merge window is run by the platform's `MergeRequestProcessor`, which needs a window; these tests choose the tool
 * the way it does (the first in `DiffManagerEx.getMergeTools()` whose `canShow` accepts the request) and create and
 * initialise its viewer directly. Three-sided diffs open through `DiffManager.createRequestPanel`, like two-sided ones.
 */
class S6MergeSpikeTest : BasePlatformTestCase() {

    private val diffViewers = mutableListOf<Pair<DiffRequest, FrameDiffTool.DiffViewer>>()

    override fun setUp() {
        super.setUp()
        val recorder = object : DiffExtension() {
            override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
                diffViewers += request to viewer
            }
        }
        ExtensionTestUtil.addExtensions(DiffExtension.EP_NAME, listOf(recorder), testRootDisposable)
    }

    fun `test a Java three-sided diff gets the placeholder with its side titles`() {
        enablePreview()
        val request = SimpleDiffRequest(
            "OrderService.java",
            DiffContents.vcsRevision(project, LEFT, "/repo/OrderService.java"),
            DiffContents.vcsRevision(project, BASE, "/repo/OrderService.java"),
            DiffContents.vcsRevision(project, RIGHT, "/repo/OrderService.java"),
            "main",
            "Base",
            "feature",
        )
        val viewer = openDiff(request)

        assertInstanceOf(viewer, ThreesideTextDiffViewer::class.java)
        val placeholder = placeholderIn(viewer.component) ?: error("no placeholder in the three-sided diff")
        assertEquals(MergeOutlinePlaceholder.Kind.THREE_SIDED_DIFF, placeholder.kind)
        assertEquals("JAVA", placeholder.languageId)
        assertEquals("main", placeholder.leftTitle)
        assertEquals("feature", placeholder.rightTitle)
    }

    fun `test the merge window chooses the SMART Diff tool and keeps the platform's own viewer`() {
        enablePreview()
        val output = EditorFactory.getInstance().createDocument(CONFLICTED_FILE)
        val request = mergeRequest(JavaFileType.INSTANCE, output, listOf(GIT_REBASE_LEFT_TITLE, "Base", GIT_REBASE_RIGHT_TITLE))
        val context = mergeContext()

        val tool = fittedTool(context, request)
        assertInstanceOf(tool, MergeOutlineTool::class.java)

        val viewer = tool.createComponent(context, request)
        try {
            // The platform's viewer object itself, so code that looks for it by type (Annotate, import resolution) still finds it.
            assertEquals("com.intellij.diff.merge.TextMergeViewer", viewer.javaClass.name)
            val placeholder = placeholderIn(viewer.component) ?: error("no placeholder in the merge window")
            assertEquals(MergeOutlinePlaceholder.Kind.MERGE_WINDOW, placeholder.kind)
            assertEquals("JAVA", placeholder.languageId)
            // Git's rebase titles are HTML with the branch in bold (GitRebaseMergeDialogCustomizer).
            assertEquals("Your version, branch feature/rates", placeholder.leftTitle)
            assertEquals("Changes from branch main, revision 1a2b3c4d", placeholder.rightTitle)

            val toolbar = viewer.init()
            assertNotNull(toolbar.toolbarActions)
            // The result pane holds the file with conflict markers until the first comparison sets it to the base.
            PlatformTestUtil.waitWithEventsDispatching({ "the result is still ${output.text}" }, { output.text == BASE }, 10)

            // The merge window runs no DiffExtension: its inner three-sided viewer is never offered to one (Option A fails).
            assertEmpty(diffViewers)
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun `test a merge in a language without an engine is left to the platform`() {
        enablePreview()
        val request = mergeRequest(PlainTextFileType.INSTANCE, EditorFactory.getInstance().createDocument(""), listOf("left", "Base", "right"))
        val tool = fittedTool(mergeContext(), request)
        assertFalse(tool is MergeOutlineTool)
    }

    fun `test the preview is off by default`() {
        val request = mergeRequest(JavaFileType.INSTANCE, EditorFactory.getInstance().createDocument(""), listOf("left", "Base", "right"))
        assertFalse(MergeOutlineTool().canShow(mergeContext(), request))

        val viewer = openDiff(
            SimpleDiffRequest(
                "OrderService.java",
                DiffContents.vcsRevision(project, LEFT, "/repo/OrderService.java"),
                DiffContents.vcsRevision(project, BASE, "/repo/OrderService.java"),
                DiffContents.vcsRevision(project, RIGHT, "/repo/OrderService.java"),
                "left",
                "Base",
                "right",
            ),
        )
        assertNull(placeholderIn(viewer.component))
    }

    /**
     * Decision T3 needs the merge window's conflicts. The viewer's own change list is internal API, but the same
     * conflicts follow from the three texts through public API, as the viewer computes them.
     */
    fun `test the merge conflicts can be computed from the three texts through public API`() {
        val texts = listOf(LEFT, BASE, RIGHT)
        val policy = ComparisonPolicy.DEFAULT
        val fragments = ComparisonManager.getInstance().mergeLines(LEFT, BASE, RIGHT, policy, DumbProgressIndicator.INSTANCE)
        val offsets = texts.map(LineOffsetsUtil::create)
        val types = fragments.map { MergeRangeUtil.getLineMergeType(it, texts, offsets, policy) }

        val baseLines = fragments.map { it.getStartLine(ThreeSide.BASE) until it.getEndLine(ThreeSide.BASE) }
        assertEquals(listOf(2 until 3, 6 until 7), baseLines)
        assertEquals(listOf(MergeConflictType.Type.CONFLICT, MergeConflictType.Type.MODIFIED), types.map { it.type })
        assertFalse(types[1].isChange(ThreeSide.LEFT))
        assertTrue(types[1].isChange(ThreeSide.RIGHT))
    }

    private fun enablePreview() {
        Registry.get(MergeOutlinePreview.REGISTRY_KEY).setValue(true, testRootDisposable)
    }

    private fun openDiff(request: SimpleDiffRequest): FrameDiffTool.DiffViewer {
        val panel = DiffManager.getInstance().createRequestPanel(project, Disposer.newDisposable(testRootDisposable), null)
        panel.setRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("no diff viewer was created", { diffViewers.any { it.first === request } }, 10)
        return diffViewers.last { it.first === request }.second
    }

    private fun mergeRequest(fileType: FileType, output: Document, titles: List<String>) =
        DiffRequestFactory.getInstance().createMergeRequest(project, fileType, output, listOf(LEFT, BASE, RIGHT), "Merge", titles, null)
            as TextMergeRequest

    private fun fittedTool(context: MergeContext, request: TextMergeRequest): MergeTool =
        DiffManagerEx.getInstance().mergeTools.first { it.canShow(context, request) }

    private fun mergeContext(): MergeContext = object : MergeContext() {
        override fun getProject(): Project = this@S6MergeSpikeTest.project
        override fun isFocusedInWindow(): Boolean = false
        override fun requestFocusInWindow() {}
        override fun finishMerge(result: MergeResult) {}
    }

    private fun placeholderIn(component: JComponent): MergeOutlinePlaceholder? =
        UIUtil.findComponentOfType(component, MergeOutlinePlaceholder::class.java)

    private companion object {
        const val BASE = "class OrderService {\n    int total(int a) {\n        return a;\n    }\n\n    boolean validate(int a) {\n        return a > 0;\n    }\n}\n"
        const val LEFT = "class OrderService {\n    int total(int a) {\n        return a + 1;\n    }\n\n    boolean validate(int a) {\n        return a > 0;\n    }\n}\n"
        const val RIGHT = "class OrderService {\n    int total(int a) {\n        return a * 2;\n    }\n\n    boolean validate(int a) {\n        return a >= 0;\n    }\n}\n"
        const val CONFLICTED_FILE = "class OrderService {\n<<<<<<< HEAD\n=======\n>>>>>>> 1a2b3c4d\n}\n"
        const val GIT_REBASE_LEFT_TITLE = "<html>Your version, branch <b>feature/rates</b></html>"
        const val GIT_REBASE_RIGHT_TITLE = "<html>Changes from branch <b>main</b>, revision 1a2b3c4d</html>"
    }
}
