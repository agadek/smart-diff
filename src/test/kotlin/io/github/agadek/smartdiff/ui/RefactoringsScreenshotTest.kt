package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * Generates the refactorings view's review screenshots from real diff viewers, without a window. It runs only with
 * `SMARTDIFF_SCREENSHOTS=1`, so the normal test run touches no files; the images are checked in under
 * `docs/screenshots/refactorings-view` and linked from the pull request.
 */
class RefactoringsScreenshotTest : BasePlatformTestCase() {

    private val created = mutableListOf<Pair<DiffRequest, FrameDiffTool.DiffViewer>>()
    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        saved = settings.state
        if (!ENABLED) return
        ExtensionTestUtil.addExtensions(
            DiffExtension.EP_NAME,
            listOf(object : DiffExtension() {
                override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
                    created += request to viewer
                }
            }),
            testRootDisposable,
        )
        OUTPUT.mkdirs()
    }

    override fun tearDown() {
        try {
            saved?.let(settings::loadState)
        } finally {
            super.tearDown()
        }
    }

    fun `test render the refactorings view in Java Kotlin and Scala`() {
        if (!ENABLED) return
        javaRenameUsages("java-rename-usages-refactorings-light")
        javaRenameUsages("java-rename-usages-refactorings-dark", dark = true)
        javaRenameUsages("java-rename-usages-selected-light", selectRename = true)
        javaRenameUsages("java-rename-usages-container-light", view = OutlineView.CONTAINER_TREE)
        javaRenameUsages("java-rename-usages-changed-hidden", hideChanged = true)
        javaRenameUsages("java-rename-usages-rename-hidden", hideRename = true)
        fixture("java-move-refactorings", "java/relationships/move-in-file-1", "java", "Sample.java")
        fixture("kotlin-extraction-refactorings", "kotlin/relationships/extraction-1", "kt", "Sample.kt")
        fixture("scala-inlining-refactorings", "scala3/relationships/inlining-1", "scala", "Sample.scala")
    }

    private fun javaRenameUsages(
        name: String,
        dark: Boolean = false,
        view: OutlineView = OutlineView.REFACTORINGS,
        hideChanged: Boolean = false,
        hideRename: Boolean = false,
        selectRename: Boolean = false,
    ) {
        val render = {
            withSettings {
                val local = myFixture.addFileToProject("shot-$name/Order.java", USAGE_NEW).virtualFile
                val shown = open(
                    SimpleDiffRequest(
                        "Order.java",
                        DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"),
                        DiffContents.localFile(project, local),
                        "HEAD",
                        "Current",
                    ),
                )
                awaitResult(shown.outline) { state -> labels(state.items).any { it.contains("usages renamed") } }
                shown.outline.setOutlineView(view)
                if (hideChanged) shown.outline.setHidden(ChangeType.CHANGED, true)
                if (hideRename) shown.outline.setHiddenRelationshipKind(io.github.agadek.smartdiff.engine.api.RelationshipKind.RENAME, true)
                if (selectRename) select(shown.outline, "rename total → sum, 1 usage  high")
                paint(shown, File(OUTPUT, "$name.png"))
            }
        }
        if (dark) dark(render) else render()
    }

    private fun fixture(name: String, directory: String, extension: String, fileName: String) = withSettings {
        val folder = File("src/test/testData/$directory")
        val old = File(folder, "old.$extension").readText()
        val new = File(folder, "new.$extension").readText()
        val shown = open(history(old, new, fileName))
        awaitResult(shown.outline) { true }
        shown.outline.setOutlineView(OutlineView.REFACTORINGS)
        paint(shown, File(OUTPUT, "$name.png"))
    }

    /** Each image starts from the settings it was opened with, so one filter does not change the next image. */
    private fun withSettings(render: () -> Unit) {
        val before = settings.state
        try {
            render()
        } finally {
            settings.loadState(before)
        }
    }

    private fun dark(render: () -> Unit) {
        val manager = EditorColorsManager.getInstance()
        val previous = manager.globalScheme
        val previousLaf = javax.swing.UIManager.getLookAndFeel()
        val scheme = manager.getScheme("Darcula") ?: previous
        manager.setGlobalScheme(scheme)
        JBColor.setDark(true)
        try {
            javax.swing.UIManager.setLookAndFeel(DarculaLaf())
        } catch (e: Exception) {
            // The editor scheme still makes the code dark; the panel keeps the test IDE's light controls.
        }
        try {
            render()
        } finally {
            manager.setGlobalScheme(previous)
            JBColor.setDark(false)
            if (previousLaf != null) javax.swing.UIManager.setLookAndFeel(previousLaf)
        }
    }

    /**
     * Paints the two editors and the outline panel side by side, so the image shows the usages' colours as well as the
     * view. It avoids the diff's divider, which needs an on-screen glass pane that a test has no window for.
     */
    private fun paint(shown: Shown, file: File) {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, WIDTH, HEIGHT)
            val editorWidth = (WIDTH - OUTLINE_WIDTH) / 2
            paintPart(graphics, shown.viewer.editor1.component, 0, 0, editorWidth, HEIGHT)
            paintPart(graphics, shown.viewer.editor2.component, editorWidth, 0, editorWidth, HEIGHT)
            paintPart(graphics, shown.outline.panel, editorWidth * 2, 0, OUTLINE_WIDTH, HEIGHT)
        } finally {
            graphics.dispose()
        }
        ImageIO.write(image, "png", file)
    }

    private fun paintPart(graphics: java.awt.Graphics, component: Component, x: Int, y: Int, width: Int, height: Int) {
        component.setSize(width, height)
        layout(component)
        val copy = graphics.create()
        try {
            copy.translate(x, y)
            component.paint(copy)
        } finally {
            copy.dispose()
        }
    }

    private fun layout(component: Component) {
        if (component !is Container) return
        component.doLayout()
        component.components.forEach(::layout)
    }

    private inner class Shown(val panel: DiffRequestPanel, val disposable: Disposable, val viewer: TwosideTextDiffViewer) {
        val outline: OutlineController = OutlineController.of(viewer) ?: error("no outline attached")
    }

    private fun open(request: SimpleDiffRequest): Shown {
        val disposable = Disposer.newDisposable(testRootDisposable)
        val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        panel.setRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("no diff viewer was created", { created.any { it.first === request } }, 10)
        return Shown(panel, disposable, created.last { it.first === request }.second as TwosideTextDiffViewer)
    }

    private fun history(old: String, new: String, fileName: String): SimpleDiffRequest = SimpleDiffRequest(
        fileName,
        DiffContents.vcsRevision(project, old, "/repo/$fileName"),
        DiffContents.vcsRevision(project, new, "/repo/$fileName"),
        "HEAD",
        "Working tree",
    )

    private fun awaitResult(outline: OutlineController, condition: (OutlineState.Shown) -> Boolean): OutlineState.Shown {
        PlatformTestUtil.waitWithEventsDispatching(
            { "no matching outline; state is ${outline.state}" },
            { (outline.state as? OutlineState.Shown)?.let(condition) == true },
            20,
        )
        return outline.state as OutlineState.Shown
    }

    private fun labels(items: List<OutlineItem>): List<String> = items.flatMap { listOf(it.label) + labels(it.children) }

    private fun select(outline: OutlineController, label: String) {
        val root = outline.panel.tree.model.root as DefaultMutableTreeNode
        val node = root.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }
            .single { (it.userObject as? OutlineItem)?.label == label }
        outline.panel.tree.selectionPath = TreePath(node.path)
    }

    private companion object {
        const val ENABLED_KEY = "SMARTDIFF_SCREENSHOTS"
        val ENABLED = System.getenv(ENABLED_KEY) == "1"
        val OUTPUT = File("docs/screenshots/refactorings-view")
        const val WIDTH = 1500
        const val HEIGHT = 920
        const val OUTLINE_WIDTH = 480

        const val USAGE_OLD = "class Order {\n    int total() { return 2; }\n    int twice() { return total() * 2; }\n}\n"
        const val USAGE_NEW = "class Order {\n    int sum() { return 2; }\n    int twice() { return sum() * 2; }\n}\n"
    }
}
