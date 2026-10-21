package io.github.agadek.smartdiff.spike

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffTool
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.impl.DiffSettingsHolder.DiffSettings
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.UIUtil

/**
 * Spike S1 (technical specification §13): can the outline attach to the native diff (Option A) or an alternate viewer
 * (Option B, §9.1) for local-change, VCS-history and "Compare with…" diffs, in Java and Kotlin?
 *
 * Each diff is opened through `DiffManager.createRequestPanel`, which runs the same `DiffRequestProcessor` as diff windows and
 * editor tabs, so viewer creation and `DiffExtension` calls are the IDE's own.
 */
class S1DiffIntegrationSpikeTest : BasePlatformTestCase() {

    private class Created(val viewer: FrameDiffTool.DiffViewer, val request: DiffRequest, val optionA: OnePixelSplitter?)

    private val created = mutableListOf<Created>()

    override fun setUp() {
        super.setUp()
        val extension = object : DiffExtension() {
            override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
                created += Created(viewer, request, OptionA.attach(viewer))
            }
        }
        ExtensionTestUtil.addExtensions(DiffExtension.EP_NAME, listOf(extension), testRootDisposable)
    }

    fun `test option A attaches to local-change diffs`() {
        for (language in Language.entries) {
            val request = SimpleDiffRequest(
                "local change",
                SpikeContents.vcsRevision(project, language.old, "/repo/${language.fileName}"),
                SpikeContents.localFile(project, myFixture.addFileToProject("local/${language.fileName}", language.new).virtualFile),
                "HEAD",
                "Current",
            )
            assertOptionAAttached(language, "local change", request)
        }
    }

    fun `test option A attaches to VCS-history diffs`() {
        for (language in Language.entries) {
            val request = SimpleDiffRequest(
                "history",
                SpikeContents.vcsRevision(project, language.old, "/repo/${language.fileName}"),
                SpikeContents.vcsRevision(project, language.new, "/repo/${language.fileName}"),
                "abc123",
                "def456",
            )
            assertOptionAAttached(language, "VCS history", request)
        }
    }

    fun `test option A attaches to compare-with diffs`() {
        for (language in Language.entries) {
            val request = SimpleDiffRequest(
                "compare",
                SpikeContents.localFile(project, myFixture.addFileToProject("a/${language.fileName}", language.old).virtualFile),
                SpikeContents.localFile(project, myFixture.addFileToProject("b/${language.fileName}", language.new).virtualFile),
                "a",
                "b",
            )
            assertOptionAAttached(language, "compare with", request)
        }
    }

    fun `test option A outline can be hidden and shown without touching the editors`() {
        val language = Language.KOTLIN
        val request = SimpleDiffRequest(
            "toggle",
            SpikeContents.vcsRevision(project, language.old, "/repo/${language.fileName}"),
            SpikeContents.vcsRevision(project, language.new, "/repo/${language.fileName}"),
            "old",
            "new",
        )
        val shown = open(request)
        val viewer = shown.viewer as TwosideTextDiffViewer
        val splitter = shown.optionA!!
        val outline = splitter.secondComponent

        splitter.secondComponent = null
        assertFalse(UIUtil.isAncestor(viewer.component, outline))
        assertTrue(UIUtil.isAncestor(viewer.component, viewer.editor1.component))
        assertTrue(UIUtil.isAncestor(viewer.component, viewer.editor2.component))

        splitter.secondComponent = outline
        assertTrue(UIUtil.isAncestor(viewer.component, outline))
    }

    fun `test native viewer toolbar includes the group plugins add buttons to`() {
        val language = Language.JAVA
        val request = SimpleDiffRequest(
            "toolbar",
            SpikeContents.vcsRevision(project, language.old, "/repo/${language.fileName}"),
            SpikeContents.vcsRevision(project, language.new, "/repo/${language.fileName}"),
            "old",
            "new",
        )
        val viewer = SimpleDiffTool.INSTANCE.createComponent(TestContext(project), request)
        Disposer.register(testRootDisposable, viewer)

        val toolbar = viewer.init().toolbarActions.orEmpty()

        assertContainsElements(toolbar, ActionManager.getInstance().getAction(IdeActions.DIFF_VIEWER_TOOLBAR))
        spike("S1 native viewer toolbar contains group ${IdeActions.DIFF_VIEWER_TOOLBAR}")
    }

    fun `test option B shows an alternate viewer that embeds the native one`() {
        val settings = DiffSettings.getSettings(null)
        val savedOrder = settings.diffToolsOrder
        ExtensionTestUtil.addExtensions(DiffTool.EP_NAME, listOf(OptionBDiffTool()), testRootDisposable)
        settings.diffToolsOrder = listOf(OptionBDiffTool::class.java.canonicalName)
        try {
            for (language in Language.entries) {
                val request = SimpleDiffRequest(
                    "option B",
                    SpikeContents.vcsRevision(project, language.old, "/repo/${language.fileName}"),
                    SpikeContents.localFile(project, myFixture.addFileToProject("optionb/${language.fileName}", language.new).virtualFile),
                    "HEAD",
                    "Current",
                )
                val shown = open(request)
                val viewer = shown.viewer as? OptionBViewer
                    ?: error("expected the Option B viewer, got ${shown.viewer.javaClass.name}")
                val native = viewer.native as TwosideTextDiffViewer

                assertTrue(UIUtil.isAncestor(viewer.component, native.editor1.component))
                assertTrue(UIUtil.isAncestor(viewer.component, native.editor2.component))
                // DiffExtensions, including other plugins' ones, now see the wrapper rather than the native viewer.
                assertNull(shown.optionA)
                spike("S1 option B ${language.name}: DiffExtension receives ${shown.viewer.javaClass.simpleName}, native viewer ${native.javaClass.simpleName} is embedded")
            }
        } finally {
            settings.diffToolsOrder = savedOrder
        }
    }

    private fun assertOptionAAttached(language: Language, kind: String, request: SimpleDiffRequest) {
        val shown = open(request)
        val viewer = shown.viewer as? TwosideTextDiffViewer
            ?: error("$kind ${language.name}: expected a two-sided text viewer, got ${shown.viewer.javaClass.name}")
        val outline = shown.optionA?.secondComponent ?: error("$kind ${language.name}: Option A did not attach")

        assertTrue(UIUtil.isAncestor(viewer.component, outline))
        assertTrue(UIUtil.isAncestor(viewer.component, viewer.editor1.component))
        assertTrue(UIUtil.isAncestor(viewer.component, viewer.editor2.component))
        assertEquals(language.old, viewer.editor1.document.text)
        assertEquals(language.new, viewer.editor2.document.text)
        val sides = request.contents.joinToString { (it as DocumentContent).javaClass.simpleName }
        spike("S1 option A $kind ${language.name}: ${viewer.javaClass.simpleName}, contents $sides, editable ${viewer.editor1.document.isWritable}/${viewer.editor2.document.isWritable}")
    }

    private fun open(request: DiffRequest): Created {
        created.clear()
        val disposable = Disposer.newDisposable(testRootDisposable)
        val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        panel.setRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("no diff viewer was created", { created.any { it.request === request } }, 10)
        return created.single { it.request === request }
    }

    private class TestContext(private val project: Project) : DiffContext() {
        override fun getProject(): Project = project
        override fun isWindowFocused(): Boolean = false
        override fun isFocusedInWindow(): Boolean = false
        override fun requestFocusInWindow() {}
    }

    private enum class Language(val fileName: String, val old: String, val new: String) {
        JAVA(
            "Greeter.java",
            "class Greeter {\n    String hello() { return \"hi\"; }\n}\n",
            "class Greeter {\n    String hello() { return \"hello\"; }\n\n    void bye() {}\n}\n",
        ),
        KOTLIN(
            "Greeter.kt",
            "class Greeter {\n    fun hello() = \"hi\"\n}\n",
            "class Greeter {\n    fun hello() = \"hello\"\n\n    fun bye() {}\n}\n",
        ),
    }
}

/** Prints a spike finding; the Gradle test task copies these lines into the build log. */
internal fun spike(message: String) = println("SPIKE $message")
