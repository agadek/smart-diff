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
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
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
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipEndpoint
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.lines.LineEngine
import io.github.agadek.smartdiff.engine.lines.LineEngineFactory
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import io.github.agadek.smartdiff.engine.registry.EngineDefaults
import io.github.agadek.smartdiff.references.ReferencePass
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.KeyStroke
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

    fun `test an inlined helper shows both notes and frames the new side`() {
        // relationships/05-inlining-detection R6, R7, R9, R10, R13
        val old = """
            class Order {
                String render(String name) {
                    return heading(name) + "!";
                }

                private String heading(String name) {
                    String h = "Report: " + name;
                    return h;
                }
            }
        """.trimIndent()
        val new = """
            class Order {
                String render(String name) {
                    String h = "Report: " + name;
                    return h + "!";
                }
            }
        """.trimIndent()
        val shown = open(history(old, new, "Order.java"))
        val outline = shown.awaitResult()

        assertEquals("1 changed · 1 inlined", outline.panel.summary.text)
        assertEquals(
            listOf(
                "class Order",
                "  method render(String)  changed, code inlined from heading(String)",
                "  method heading(String)  removed, inlined into render(String)",
            ),
            rows(outline),
        )

        select(outline, "method heading(String)  removed, inlined into render(String)")
        assertHighlightedAt(shown.viewer.editor1, old.indexOf("private String heading"))
        assertHighlightedAt(shown.viewer.editor2, new.indexOf("String h ="))

        select(outline, "method render(String)  changed, code inlined from heading(String)")
        assertHighlightedAt(shown.viewer.editor2, new.indexOf("String h ="))
    }

    fun `test a selected moved row keeps its frames aligned while scrolling`() {
        // outline/01-structural-outline R25
        val shown = open(history(MOVED_OLD, MOVED_NEW, "Moved.java"))
        val outline = shown.awaitResult()
        show(shown.viewer.editor1)
        show(shown.viewer.editor2)
        val item = selectItem(outline) { it.title == "method moved()" }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val sync = requireNotNull(shown.viewer.syncScrollSupport) { "the native viewer has no synchronized scrolling" }
        assertTrue("the selection holds synchronized scrolling", sync.isDuringSyncScroll)

        val old = shown.viewer.editor1
        val new = shown.viewer.editor2
        val oldFrame = scrollFrame(old, requireNotNull(item.oldRange))
        val newFrame = scrollFrame(new, requireNotNull(item.newRange))
        old.scrollingModel.scrollVertically(old.scrollingModel.visibleArea.y + old.lineHeight * 3)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue("a small scroll keeps both frames in view", sync.isDuringSyncScroll)
        assertEquals(
            "the old frame keeps the same place in view",
            old.scrollingModel.visibleArea.y - oldFrame.start,
            new.scrollingModel.visibleArea.y - newFrame.start,
            1.0,
        )
    }

    fun `test a fold inside a selected frame keeps the frames aligned`() {
        // outline/01-structural-outline R25
        val shown = open(history(MOVED_OLD, MOVED_NEW, "Moved.java"))
        val outline = shown.awaitResult()
        show(shown.viewer.editor1)
        show(shown.viewer.editor2)
        val item = selectItem(outline) { it.title == "method moved()" }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val sync = requireNotNull(shown.viewer.syncScrollSupport) { "the native viewer has no synchronized scrolling" }
        assertTrue(sync.isDuringSyncScroll)

        val old = shown.viewer.editor1
        val new = shown.viewer.editor2
        val before = scrollFrame(old, requireNotNull(item.oldRange))
        old.foldingModel.runBatchFoldingOperation {
            old.foldingModel.addFoldRegion(
                old.document.getLineStartOffset(62),
                old.document.getLineEndOffset(63),
                "...",
            )?.isExpanded = false
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val folded = scrollFrame(old, requireNotNull(item.oldRange))
        assertTrue("the fold shortened the old frame", folded.end < before.end)
        assertTrue("the fold keeps the hold", sync.isDuringSyncScroll)

        old.scrollingModel.disableAnimation()
        try {
            old.scrollingModel.scrollVertically(old.scrollingModel.verticalScrollOffset + old.lineHeight * 5)
        } finally {
            old.scrollingModel.enableAnimation()
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val oldFrame = scrollFrame(old, requireNotNull(item.oldRange))
        val newFrame = scrollFrame(new, requireNotNull(item.newRange))
        val oldProgress = (old.scrollingModel.visibleArea.y - oldFrame.start) / (oldFrame.end - oldFrame.start)
        val newProgress = (new.scrollingModel.visibleArea.y - newFrame.start) / (newFrame.end - newFrame.start)
        assertEquals("the frames keep the same progress after the fold", oldProgress, newProgress, 0.05)
    }

    fun `test a selected moved row releases synchronized scrolling when a frame leaves view`() {
        // outline/01-structural-outline R26
        val shown = open(history(MOVED_OLD, MOVED_NEW, "Moved.java"))
        val outline = shown.awaitResult()
        show(shown.viewer.editor1)
        show(shown.viewer.editor2)
        selectItem(outline) { it.title == "method moved()" }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val sync = requireNotNull(shown.viewer.syncScrollSupport) { "the native viewer has no synchronized scrolling" }
        assertTrue(sync.isDuringSyncScroll)

        shown.viewer.editor1.scrollingModel.scrollVertically(0)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertFalse("the pause ends with the frame out of view", sync.isDuringSyncScroll)
    }

    fun `test changing or clearing the selection replaces or releases the scroll hold`() {
        // outline/01-structural-outline R26
        val shown = open(history(MOVED_OLD, MOVED_NEW, "Moved.java"))
        val outline = shown.awaitResult()
        show(shown.viewer.editor1)
        show(shown.viewer.editor2)
        selectItem(outline) { it.title == "method moved()" }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val sync = requireNotNull(shown.viewer.syncScrollSupport) { "the native viewer has no synchronized scrolling" }
        assertTrue(sync.isDuringSyncScroll)

        selectItem(outline) { it.title == "class Moved" }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("the hold moves to the new selection", sync.isDuringSyncScroll)

        outline.panel.tree.selectionPath = null
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse("clearing the selection releases the hold", sync.isDuringSyncScroll)
    }

    fun `test an edit releases the scroll hold`() {
        // outline/01-structural-outline R26
        val local = myFixture.addFileToProject("local/Moved.java", MOVED_NEW).virtualFile
        val shown = open(
            SimpleDiffRequest(
                "local",
                DiffContents.vcsRevision(project, MOVED_OLD, "/repo/Moved.java"),
                DiffContents.localFile(project, local),
                "HEAD",
                "Current",
            ),
        )
        val outline = shown.awaitResult()
        show(shown.viewer.editor1)
        show(shown.viewer.editor2)
        selectItem(outline) { it.title == "method moved()" }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val sync = requireNotNull(shown.viewer.syncScrollSupport) { "the native viewer has no synchronized scrolling" }
        assertTrue(sync.isDuringSyncScroll)

        val document = shown.viewer.editor2.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "// edited\n")
        }

        assertFalse("the edit releases the hold before the next result", sync.isDuringSyncScroll)
    }

    fun `test disposing the diff releases the scroll hold`() {
        // outline/01-structural-outline R26
        val shown = open(history(MOVED_OLD, MOVED_NEW, "Moved.java"))
        val outline = shown.awaitResult()
        show(shown.viewer.editor1)
        show(shown.viewer.editor2)
        selectItem(outline) { it.title == "method moved()" }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val sync = requireNotNull(shown.viewer.syncScrollSupport) { "the native viewer has no synchronized scrolling" }
        assertTrue(sync.isDuringSyncScroll)

        Disposer.dispose(shown.disposable)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertFalse("closing the diff releases the hold", sync.isDuringSyncScroll)
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

    fun `test a rebuild keeps the selected row without scrolling the editors`() {
        // outline/01 R27
        val shown = open(history(MOVED_OLD, MOVED_NEW, "Moved.java"))
        val outline = shown.awaitResult()
        show(shown.viewer.editor1)
        show(shown.viewer.editor2)
        val selected = selectItem(outline) { it.title == "method moved()" }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        shown.viewer.editor1.scrollingModel.scrollVertically(0)
        shown.viewer.editor2.scrollingModel.scrollVertically(0)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val oldArea = shown.viewer.editor1.scrollingModel.visibleArea
        val newArea = shown.viewer.editor2.scrollingModel.visibleArea

        outline.setHidden(ChangeType.ADDED, true)

        assertEquals("the old editor must not scroll on rebuild", oldArea, shown.viewer.editor1.scrollingModel.visibleArea)
        assertEquals("the new editor must not scroll on rebuild", newArea, shown.viewer.editor2.scrollingModel.visibleArea)
        assertEquals(selected.label, outline.panel.selectedItem()?.label)
        assertTrue("the row is framed again", outline.navigator.shown.isNotEmpty())
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

    fun `test outline 02 R7 the header hide button turns the outline off for this diff window`() {
        // outline/02-outline-toggle R7
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        assertEquals(AllIcons.General.HideToolWindow, outline.panel.hideAction.templatePresentation.icon)

        outline.panel.hideAction.actionPerformed(TestActionEvent.createTestEvent(outline.panel.hideAction))

        assertFalse(outline.isOutlineVisible)
        assertEquals(OutlineState.Hidden, outline.state)
        assertFalse(UIUtil.isAncestor(shown.viewer.component, outline.panel))
        assertFalse(UIUtil.isAncestor(shown.viewer.component, outline.strip))

        // The window keeps the choice for its next file; another window keeps its own.
        val next = shown.show(history(KOTLIN_OLD, KOTLIN_NEW, "Greeter.kt"))
        assertFalse(next.outline.isOutlineVisible)
        assertTrue(open(history(JAVA_OLD, JAVA_NEW, "Other.java")).outline.isOutlineVisible)
    }

    fun `test outline 02 R8 R9 R11 the minimise button shows the summary strip and restores the same result`() {
        // outline/02-outline-toggle R8, R9, R11
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()

        outline.panel.minimiseAction.actionPerformed(TestActionEvent.createTestEvent(outline.panel.minimiseAction))

        assertTrue(outline.isMinimised)
        assertTrue(outline.isOutlineVisible)
        assertTrue(UIUtil.isAncestor(shown.viewer.component, outline.strip))
        assertFalse(UIUtil.isAncestor(shown.viewer.component, outline.panel))
        assertTrue(outline.strip.toolTipText, outline.strip.toolTipText.contains("1 changed · 1 added"))
        assertTrue(
            outline.strip.restoreButton.accessibleContext.accessibleName.orEmpty().contains("1 changed · 1 added"),
        )

        val shownState = outline.state
        outline.strip.restoreButton.doClick()

        assertFalse(outline.isMinimised)
        assertTrue(UIUtil.isAncestor(shown.viewer.component, outline.panel))
        assertFalse(UIUtil.isAncestor(shown.viewer.component, outline.strip))
        assertSame("restoring reuses the computed result", shownState, outline.state)
        assertEquals("1 changed · 1 added", outline.panel.summary.text)
    }

    fun `test outline 02 R9 minimising before a fallback result arrives names the fallback`() {
        // outline/02-outline-toggle R9
        val engine = DelayedFallbackEngine()
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(factory(engine)), testRootDisposable)
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.outline
        assertEquals(OutlineState.Loading, outline.state)

        outline.panel.minimiseAction.actionPerformed(TestActionEvent.createTestEvent(outline.panel.minimiseAction))
        assertTrue(outline.isMinimised)
        assertTrue(outline.strip.toolTipText, outline.strip.toolTipText.contains("Comparing"))

        engine.finish()
        awaitResult(outline) { it.result.status == Status.FAILED }

        assertTrue(
            "the strip names the fallback: ${outline.strip.toolTipText}",
            outline.strip.toolTipText.contains("Analysis failed: analysis failed after a delay"),
        )
        assertFalse(outline.strip.toolTipText, outline.strip.toolTipText.contains("No structural changes"))
    }

    fun `test outline 02 R9 minimising after a fallback result names the fallback`() {
        // outline/02-outline-toggle R9
        val engine = ResultEngine()
        maskEngines(engine)
        for ((status, reason, expected) in listOf(
            Triple(Status.UNSUPPORTED, "not a source file", "Not supported: not a source file"),
            Triple(Status.TOO_LARGE, "file has 100 characters", "Too large to analyze: file has 100 characters"),
            Triple(Status.FAILED, "could not read the file", "Analysis failed: could not read the file"),
        )) {
            engine.status = status
            engine.reason = reason
            val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()

            outline.panel.minimiseAction.actionPerformed(TestActionEvent.createTestEvent(outline.panel.minimiseAction))

            assertTrue("$status: ${outline.strip.toolTipText}", outline.strip.toolTipText.contains(expected))
            assertFalse("$status: ${outline.strip.toolTipText}", outline.strip.toolTipText.contains("No structural changes"))
        }
    }

    fun `test outline 02 R10 Enter restores the strip and focus is visible`() {
        // outline/02-outline-toggle R10
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        outline.panel.minimiseAction.actionPerformed(TestActionEvent.createTestEvent(outline.panel.minimiseAction))
        val button = outline.strip.restoreButton

        val idle = button.border
        button.focusListeners.forEach { it.focusGained(FocusEvent(button, FocusEvent.FOCUS_GAINED)) }
        assertNotSame("focus shows a visible border for Tab", idle, button.border)

        val actionKey = button.inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
        assertNotNull("Enter is bound to the strip", actionKey)
        requireNotNull(button.actionMap.get(actionKey))
            .actionPerformed(ActionEvent(button, ActionEvent.ACTION_PERFORMED, actionKey.toString()))

        assertFalse(outline.isMinimised)
        assertTrue(UIUtil.isAncestor(shown.viewer.component, outline.panel))
    }

    fun `test outline 02 R10 clicking the strip restores the panel at its previous width`() {
        // outline/02-outline-toggle R10
        val key = "smartdiff.outline.proportion"
        val properties = PropertiesComponent.getInstance()
        val saved = properties.getValue(key)
        try {
            val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()
            outline.splitter.proportion = 0.6f
            val width = outline.splitter.proportion

            outline.panel.minimiseAction.actionPerformed(TestActionEvent.createTestEvent(outline.panel.minimiseAction))
            outline.strip.restoreButton.doClick()

            assertEquals(width, outline.splitter.proportion, 0.001f)
        } finally {
            if (saved == null) properties.unsetValue(key) else properties.setValue(key, saved)
        }
    }

    fun `test outline 02 R12 the toggle has a default shortcut that does not clash and Keymap lists it`() {
        // outline/02-outline-toggle R12
        val action = ActionManager.getInstance().getAction("SmartDiff.ToggleOutline")!!
        val actionId = ActionManager.getInstance().getId(action) ?: "SmartDiff.ToggleOutline"
        val expected = listOf(KeyStroke.getKeyStroke("control alt shift O"), KeyStroke.getKeyStroke("meta alt shift O"))
        val keymap = KeymapManager.getInstance().activeKeymap
        val shortcuts = keymap.getShortcuts(actionId).filterIsInstance<KeyboardShortcut>()

        assertTrue(
            "the toggle's default shortcut is missing from ${keymap.name}: $shortcuts",
            shortcuts.any { it.firstKeyStroke in expected },
        )
        for (shortcut in shortcuts) {
            assertEquals(
                "no other action is bound to ${shortcut.firstKeyStroke} in ${keymap.name}",
                listOf(actionId),
                keymap.getActionIds(shortcut.firstKeyStroke).toList(),
            )
        }
    }

    fun `test outline 02 R13 the toggle is placed first in the diff toolbar`() {
        // outline/02-outline-toggle R13
        val action = ActionManager.getInstance().getAction("SmartDiff.ToggleOutline") as ToggleOutlineAction
        val toolbar = ActionManager.getInstance().getAction(IdeActions.DIFF_VIEWER_TOOLBAR) as DefaultActionGroup

        val order = toolbar.childActionsOrStubs.map { ActionManager.getInstance().getId(it) }
        assertEquals("SmartDiff.ToggleOutline", ActionManager.getInstance().getId(action))
        assertEquals("the first toolbar action stays visible in a narrow diff; order is $order", "SmartDiff.ToggleOutline", order.first())
    }

    fun `test outline 02 R14 the minimised state and width are remembered for the next diff`() {
        // outline/02-outline-toggle R14
        val key = "smartdiff.outline.proportion"
        val properties = PropertiesComponent.getInstance()
        val saved = properties.getValue(key)
        try {
            val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
            val outline = shown.awaitResult()
            outline.splitter.proportion = 0.6f
            outline.panel.minimiseAction.actionPerformed(TestActionEvent.createTestEvent(outline.panel.minimiseAction))

            val next = shown.show(history(KOTLIN_OLD, KOTLIN_NEW, "Greeter.kt"))
            next.awaitResult()
            assertTrue("the next file in the window starts minimised", next.outline.isMinimised)
            assertTrue(UIUtil.isAncestor(next.viewer.component, next.outline.strip))
            assertEquals("the next diff keeps the width", 0.6f, next.outline.splitter.proportion, 0.01f)

            val another = open(history(JAVA_OLD, JAVA_NEW, "Other.java"))
            another.awaitResult()
            assertTrue("a new diff window starts minimised", another.outline.isMinimised)
            assertEquals("a new diff window keeps the width", 0.6f, another.outline.splitter.proportion, 0.01f)
        } finally {
            settings.outlineMinimised = false
            if (saved == null) properties.unsetValue(key) else properties.setValue(key, saved)
        }
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
        // outline/05-analysis-fallback R3
        settings.limits = Limits.DEFAULT.copy(maxChars = 10)

        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()

        assertEquals(Status.TOO_LARGE, (outline.state as OutlineState.Shown).result.status)
        assertTrue(outline.panel.message.text, outline.panel.message.text.contains("Too large to analyze"))
        assertTrue("the reason is shown", outline.panel.message.text.contains("the limit is 10"))
        assertEquals(emptyList<String>(), rows(outline))
    }

    fun `test a failed comparison shows analysis failed with the reason`() {
        // outline/05-analysis-fallback R4
        val engine = ResultEngine().apply {
            status = Status.FAILED
            reason = "could not read the file"
        }
        maskEngines(engine)

        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()

        assertEquals(Status.FAILED, (outline.state as OutlineState.Shown).result.status)
        assertTrue(outline.panel.message.text, outline.panel.message.text.contains("Analysis failed"))
        assertTrue("the reason is shown", outline.panel.message.text.contains("could not read the file"))
        assertEquals(emptyList<String>(), rows(outline))
    }

    fun `test an unsupported result shows not supported with the reason`() {
        // outline/05-analysis-fallback R12
        val engine = ResultEngine().apply {
            status = Status.UNSUPPORTED
            reason = "not a source file"
        }
        maskEngines(engine)

        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()

        assertEquals(Status.UNSUPPORTED, (outline.state as OutlineState.Shown).result.status)
        assertTrue(outline.panel.message.text, outline.panel.message.text.contains("Not supported"))
        assertTrue("the reason is shown", outline.panel.message.text.contains("not a source file"))
        assertEquals(emptyList<String>(), rows(outline))
    }

    fun `test a partial comparison shows its rows and a banner`() {
        // outline/05-analysis-fallback R6
        val engine = ResultEngine().apply {
            status = Status.PARTIAL
            reason = "some source could not be parsed and was not analyzed"
            changes = listOf(
                ChangeNode(
                    Subject.Declaration(DeclarationKind.CLASS, "Greeter", null),
                    TextRange(0, 1), TextRange(0, 1), Presence.MATCHED,
                    content = Content.CHANGED,
                ),
            )
            unanalyzed = listOf(RangePair(TextRange(0, 1), null))
        }
        maskEngines(engine)

        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()

        assertEquals(Status.PARTIAL, (outline.state as OutlineState.Shown).result.status)
        assertTrue(outline.panel.banner.isVisible)
        assertTrue(outline.panel.banner.text, outline.panel.banner.text.contains("Some source was not analyzed"))
        assertTrue("the analyzed rows are shown", rows(outline).contains("class Greeter  changed"))
    }

    fun `test every fallback keeps both editors and never claims the versions are the same`() {
        // outline/05-analysis-fallback R9, R10
        val engine = ResultEngine()
        maskEngines(engine)

        for ((status, reason) in listOf(
            Status.TOO_LARGE to "file has 100 characters, the limit is 10",
            Status.FAILED to "the engine could not analyze the file",
            Status.UNSUPPORTED to "not a source file",
        )) {
            engine.status = status
            engine.reason = reason
            val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
            val outline = shown.awaitResult()

            assertEquals(status.name, JAVA_OLD, shown.viewer.editor1.document.text)
            assertEquals(status.name, JAVA_NEW, shown.viewer.editor2.document.text)
            assertTrue(status.name, UIUtil.isAncestor(shown.viewer.component, shown.viewer.editor1.component))
            assertTrue(status.name, UIUtil.isAncestor(shown.viewer.component, shown.viewer.editor2.component))
            val message = outline.panel.message.accessibleContext.accessibleName.orEmpty()
            assertFalse("a fallback never says the versions behave the same", message.contains("behave the same", ignoreCase = true))
            assertFalse("a fallback never says there are no changes", message.contains("No structural changes", ignoreCase = true))
        }
    }

    fun `test a comparison over the time limit is canceled and shown as failed`() {
        // outline/05-analysis-fallback R5
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
        // outline/05-analysis-fallback R1, R2, R11
        // The light test IDE loads neither properties nor YAML; mask the engines so Kotlin, which is loaded, has none.
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(factory(JavaOnlyEngine())), testRootDisposable)
        val viewer = openAny(history(KOTLIN_OLD, KOTLIN_NEW, "Greeter.kt")) as TwosideTextDiffViewer
        val outline = OutlineController.of(viewer)
        assertNotNull("the outline is attached", outline)
        val controller = outline!!

        val languageName = requireNotNull(Language.findLanguageByID("kotlin")?.displayName)
        val state = controller.state as OutlineState.NoEngine
        assertEquals(languageName, state.languageName)
        val message = "No comparison engine supports $languageName files. The native diff shows every change."
        assertEquals(message, controller.panel.message.accessibleContext.accessibleName)
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

    fun `test the setting keeps the outline hidden in a fallback state`() {
        // outline/05-analysis-fallback R11
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, listOf(factory(JavaOnlyEngine())), testRootDisposable)
        settings.showOutline = false

        val shown = open(history(KOTLIN_OLD, KOTLIN_NEW, "Greeter.kt"))

        assertFalse(shown.outline.isOutlineVisible)
        assertEquals(OutlineState.Hidden, shown.outline.state)
        assertFalse(UIUtil.isAncestor(shown.viewer.component, shown.outline.panel))
    }

    fun `test a plain-text diff gets no outline`() {
        val viewer = openAny(history("one\n", "two\n", "notes.txt"))

        assertNull(OutlineController.of(viewer))
        val action = ActionManager.getInstance().getAction("SmartDiff.ToggleOutline") as ToggleOutlineAction
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getSimpleContext(DiffDataKeys.DIFF_VIEWER, viewer))
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
    }

    fun `test relationships 04 R1 R2 the engine result is replaced by the grouped result`() {
        // relationships/04-rename-grouping R1, R2, R9, R10, R11
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        val shown = open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
        val outline = shown.outline

        val shownState = awaitResult(outline) { state -> labels(state.items).any { it.contains("usages renamed") } }
        assertNull("the resolving note is gone when the pass finishes", shownState.usageNote)
        assertEquals("1 rename candidate · 1 usages renamed", outline.panel.summary.text)
        assertTrue(rows(outline).any { it.contains("method total → sum()  rename candidate, 1 usage") })
        assertTrue(rows(outline).any { it.contains("method twice()  usages renamed") })
        assertFalse("the clipboard note is not shown", outline.panel.usageNote.isVisible)
    }

    fun `test relationships 04 R2 the outline shows a resolving note while the pass runs`() {
        // relationships/04-rename-grouping R2
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        val shown = open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
        val outline = shown.outline
        val release = CountDownLatch(1)
        outline.passRunner = { request, result, cancellation ->
            release.await(10, TimeUnit.SECONDS)
            ReferencePass.runForBenchmark(project, request, result, cancellation)
        }
        outline.refresh()

        val resolving = awaitResult(outline) { it.usageNote == OutlineController.RESOLVING }
        assertTrue("the note is visible: ${outline.panel.usageNote.text}", outline.panel.usageNote.isVisible)
        assertEquals("Resolving usages…", outline.panel.usageNote.text)
        release.countDown()
        val grouped = awaitResult(outline) { state -> labels(state.items).any { it.contains("usages renamed") } }
        assertNull("the resolving note is gone when the pass finishes", grouped.usageNote)
    }

    fun `test relationships 04 R3 a clipboard diff keeps the engine result`() {
        // relationships/04-rename-grouping R3
        val outline = open(history(USAGE_OLD, USAGE_NEW, "Order.java")).awaitResult()

        assertEquals("1 changed · 1 rename candidate", outline.panel.summary.text)
        assertFalse(rows(outline).any { it.contains("usage") })
        assertFalse(outline.panel.usageNote.isVisible)
        assertEquals(null, (outline.state as OutlineState.Shown).usageNote)
    }

    fun `test relationships 04 R3 opening a diff reads the project file index off the EDT`() {
        // relationships/04-rename-grouping R3; issue #139: no slow-operation warning on the EDT
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        val checks = AtomicInteger()
        val edtChecks = AtomicInteger()
        val previous = ReferencePass.inContentCheck
        ReferencePass.inContentCheck = { project, file ->
            checks.incrementAndGet()
            if (ApplicationManager.getApplication().isDispatchThread) edtChecks.incrementAndGet()
            ProjectFileIndex.getInstance(project).isInContent(file)
        }
        try {
            val shown = open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
            awaitResult(shown.outline) { state -> labels(state.items).any { it.contains("usages renamed") } }
        } finally {
            ReferencePass.inContentCheck = previous
        }

        assertTrue("the pass checks the file index", checks.get() > 0)
        assertEquals("the project file index is never read on the EDT", 0, edtChecks.get())
    }

    fun `test relationships 04 R3 a file outside the project keeps the engine result and no note`() {
        // relationships/04-rename-grouping R3; issue #139: no Resolving note lingers when the file is not applicable
        val file = Files.createTempFile("outside-project", ".java").toFile()
        file.writeText(USAGE_NEW)
        VfsRootAccess.allowRootAccess(testRootDisposable, file.canonicalFile.parentFile.absolutePath)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        checkNotNull(virtualFile) { "the temp file was not found in the local file system" }
        try {
            val shown = open(SimpleDiffRequest("outside", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, virtualFile), "HEAD", "Current"))
            val outline = shown.outline
            val state = awaitResult(outline) { it.usageNote == null && labels(it.items).none { label -> label.contains("usages renamed") } }

            assertNull("the resolving note does not linger", state.usageNote)
            assertFalse(outline.panel.usageNote.isVisible)
            assertEquals("1 changed · 1 rename candidate", outline.panel.summary.text)
        } finally {
            file.delete()
        }
    }

    fun `test relationships 04 R8 the filter never hides a grouped usage`() {
        // relationships/04-rename-grouping R8; declarations/06-change-labels R14
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        val shown = open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
        val outline = shown.outline
        awaitResult(outline) { state -> labels(state.items).any { it.contains("usages renamed") } }

        outline.setHidden(ChangeType.CHANGED, true)

        assertTrue(rows(outline).any { it.contains("method twice()  usages renamed") })
        assertEquals("1 rename candidate · 1 usages renamed", outline.panel.summary.text)
    }

    fun `test relationships 04 R2 replacing the model keeps the selection`() {
        // relationships/04-rename-grouping R2
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        val shown = open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
        val outline = shown.outline
        val release = CountDownLatch(1)
        outline.passRunner = { request, result, cancellation ->
            release.await(10, TimeUnit.SECONDS)
            ReferencePass.runForBenchmark(project, request, result, cancellation)
        }
        outline.refresh()
        awaitResult(outline) { it.usageNote == OutlineController.RESOLVING }

        select(outline, "method total → sum()  rename candidate")
        release.countDown()

        awaitResult(outline) { state -> labels(state.items).any { it.contains("usages renamed") } }
        assertEquals("method total → sum()  rename candidate, 1 usage", outline.panel.selectedItem()?.label)
    }

    fun `test relationships 04 R11 selecting the rename frames every grouped usage`() {
        // relationships/04-rename-grouping R11
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        val shown = open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
        val outline = shown.outline
        awaitResult(outline) { state -> labels(state.items).any { it.contains("usages renamed") } }

        select(outline, "method total → sum()  rename candidate, 1 usage")

        val oldStart = USAGE_OLD.indexOf("total() * 2")
        val newStart = USAGE_NEW.indexOf("sum() * 2")
        assertTrue(
            "the old usage is highlighted: ${outline.navigator.shown.map { it.startOffset }}",
            outline.navigator.shown.any { it.document === shown.viewer.editor1.document && it.startOffset == oldStart },
        )
        assertTrue(
            "the new usage is highlighted: ${outline.navigator.shown.map { it.startOffset }}",
            outline.navigator.shown.any { it.document === shown.viewer.editor2.document && it.startOffset == newStart },
        )
    }

    fun `test relationships 04 R17 a timed out pass shows usages not grouped`() {
        // relationships/04-rename-grouping R17
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        val shown = open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
        val outline = shown.outline
        outline.passRunner = { _, result, _ -> ReferencePass.Outcome(result, ReferencePass.Status.TIMED_OUT) }
        outline.refresh()

        val state = awaitResult(outline) { it.usageNote == OutlineController.USAGES_NOT_GROUPED }
        assertTrue(outline.panel.usageNote.isVisible)
        assertEquals("usages not grouped", outline.panel.usageNote.text)
        assertFalse("no partial usage evidence is kept", labels(state.items).any { it.contains("usages renamed") })
    }

    fun `test relationships 04 R17 a dumb project shows usages not grouped`() {
        // relationships/04-rename-grouping R17
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        val shown = open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
        val outline = shown.outline
        outline.passRunner = { _, result, _ -> ReferencePass.Outcome(result, ReferencePass.Status.NOT_GROUPED, dumb = true) }
        outline.refresh()

        awaitResult(outline) { it.usageNote == OutlineController.USAGES_NOT_GROUPED }
        assertTrue(outline.panel.usageNote.isVisible)
        assertEquals("usages not grouped", outline.panel.usageNote.text)
    }

    fun `test relationships 04 R16 an edit during the pass discards its result`() {
        // relationships/04-rename-grouping R16
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        val shown = open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
        val outline = shown.outline
        val release = CountDownLatch(1)
        var calls = 0
        outline.passRunner = { request, result, cancellation ->
            if (calls++ == 0) {
                release.await(10, TimeUnit.SECONDS)
                ReferencePass.runForBenchmark(project, request, result, cancellation)
            } else {
                ReferencePass.Outcome(result, ReferencePass.Status.NOT_GROUPED)
            }
        }
        outline.refresh()
        awaitResult(outline) { it.usageNote == OutlineController.RESOLVING }

        WriteCommandAction.runWriteCommandAction(project) {
            shown.viewer.editor2.document.insertString(0, "// edited\n")
        }
        PlatformTestUtil.waitWithEventsDispatching("the edited comparison did not run", { calls >= 2 }, 20)
        release.countDown()

        val state = awaitResult(outline) { it.usageNote == null && labels(it.items).none { label -> label.contains("usages renamed") } }
        assertFalse(labels(state.items).any { it.contains("usages renamed") })
    }

    fun `test relationships 06 R1 the relationship-kind filter is hidden in the container tree`() {
        // relationships/06-refactorings-view R1
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        val event = TestActionEvent.createTestEvent(outline.panel.kindsAction)

        settings.outlineView = OutlineView.CONTAINER_TREE
        outline.panel.kindsAction.update(event)
        assertFalse("the relationship-kind filter has no effect in the container tree", event.presentation.isVisible)

        settings.outlineView = OutlineView.REFACTORINGS
        outline.panel.kindsAction.update(event)
        assertTrue("the relationship-kind filter is shown in the refactorings view", event.presentation.isVisible)
    }

    fun `test relationships 06 R1 R2 R16 R18 the toolbar switches views and keeps the summary and native diff`() {
        // relationships/06-refactorings-view R1, R2, R16, R17, R18
        val shown = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java"))
        val outline = shown.awaitResult()
        val summary = outline.panel.summary.text
        val event = TestActionEvent.createTestEvent(outline.panel.viewAction)
        assertFalse("the container tree is the default", outline.panel.viewAction.isSelected(event))
        val containerRows = rows(outline)

        outline.panel.viewAction.setSelected(event, true)

        assertEquals(OutlineView.REFACTORINGS, settings.outlineView)
        assertTrue(outline.panel.viewAction.isSelected(event))
        assertEquals(
            listOf(
                "Refactorings",
                "  No refactorings found",
                "Other changes",
                "  class Greeter",
                "    method hello()  changed",
                "    method bye()  added",
            ),
            rows(outline),
        )
        assertEquals("the summary line is the same in both views", summary, outline.panel.summary.text)
        assertTrue(UIUtil.isAncestor(shown.viewer.component, shown.viewer.editor1.component))
        assertTrue(UIUtil.isAncestor(shown.viewer.component, shown.viewer.editor2.component))

        outline.panel.viewAction.setSelected(event, false)

        assertEquals(OutlineView.CONTAINER_TREE, settings.outlineView)
        assertEquals(containerRows, rows(outline))

        outline.panel.viewAction.setSelected(event, true)
        outline.setOutlineVisible(false)
        assertFalse("the native diff stays when the outline is off", UIUtil.isAncestor(shown.viewer.component, outline.panel))
        assertTrue(UIUtil.isAncestor(shown.viewer.component, shown.viewer.editor1.component))
        assertTrue(UIUtil.isAncestor(shown.viewer.component, shown.viewer.editor2.component))
        outline.setOutlineVisible(true)
        awaitResult(outline) { state -> state.items.firstOrNull()?.title == "Refactorings" }
        assertEquals("the view choice survives hiding and showing the outline", "Refactorings", rows(outline).first())

        select(outline, "method bye()  added")
        assertEquals("Not present on the old side", outline.panel.absence.text)
    }

    fun `test relationships 06 R2 a settings page change rebuilds an open outline without comparing again`() {
        // relationships/06-refactorings-view R2
        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()
        val before = outline.state as OutlineState.Shown

        settings.outlineView = OutlineView.REFACTORINGS
        ApplicationManager.getApplication().messageBus.syncPublisher(SmartDiffSettings.CHANGED).settingsChanged()

        assertEquals("Refactorings", rows(outline).first())
        assertSame("the setting change reuses the kept result", before.result, (outline.state as OutlineState.Shown).result)

        settings.outlineView = OutlineView.CONTAINER_TREE
        ApplicationManager.getApplication().messageBus.syncPublisher(SmartDiffSettings.CHANGED).settingsChanged()

        assertEquals("class Greeter", rows(outline).first())
    }

    fun `test relationships 06 R17 the summary and hidden count are identical in both views`() {
        // relationships/06-refactorings-view R17
        val old = method("total")
        val new = method("sum")
        val engine = ResultEngine().apply {
            status = Status.ANALYZED
            changes = listOf(
                ChangeNode(new, TextRange(0, 5), TextRange(0, 3), Presence.MATCHED, content = Content.CHANGED, renamedFrom = old),
                ChangeNode(method("other"), TextRange(6, 11), TextRange(6, 11), Presence.MATCHED, content = Content.CHANGED),
            )
            relationships = listOf(
                Relationship(
                    java.util.EnumSet.of(RelationshipKind.RENAME),
                    listOf(RelationshipEndpoint(old, TextRange(0, 5))),
                    listOf(RelationshipEndpoint(new, TextRange(0, 3))),
                    Confidence.HIGH,
                    listOf(Evidence.Name("total", "sum")),
                ),
            )
        }
        maskEngines(engine)
        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()

        assertEquals("2 changed · 1 rename candidate", outline.panel.summary.text)
        val containerHidden = (outline.state as OutlineState.Shown).hiddenCount

        outline.setOutlineView(OutlineView.REFACTORINGS)

        assertEquals("the refactorings view uses the same summary line", "2 changed · 1 rename candidate", outline.panel.summary.text)
        assertEquals("the refactorings view counts the same hidden changes", containerHidden, (outline.state as OutlineState.Shown).hiddenCount)
        assertTrue(
            "the changed endpoint keeps its label: ${rows(outline)}",
            rows(outline).any { it.contains("method total → sum()  rename candidate, changed") },
        )

        outline.setHidden(ChangeType.CHANGED, true)
        assertEquals("2 changed · 1 rename candidate · 1 hidden", outline.panel.summary.text)
        val refactoringsHidden = (outline.state as OutlineState.Shown).hiddenCount

        outline.setOutlineView(OutlineView.CONTAINER_TREE)

        assertEquals("the container tree keeps the same summary after filtering", "2 changed · 1 rename candidate · 1 hidden", outline.panel.summary.text)
        assertEquals("both views count the same hidden change", refactoringsHidden, (outline.state as OutlineState.Shown).hiddenCount)
    }

    fun `test relationships 06 R10 R11 R12 selecting the rename frames every endpoint and usage with its own colour`() {
        // relationships/06-refactorings-view R10, R11, R12
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        val shown = open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
        val outline = shown.outline
        awaitResult(outline) { state -> labels(state.items).any { it.contains("usages renamed") } }
        outline.setOutlineView(OutlineView.REFACTORINGS)

        assertTrue(rows(outline).toString(), rows(outline).any { it.trimStart().startsWith("rename total → sum, 1 usage  high") })
        select(outline, "rename total → sum, 1 usage  high")

        val oldStart = USAGE_OLD.indexOf("int total()")
        val newStart = USAGE_NEW.indexOf("int sum()")
        val oldUsage = USAGE_OLD.indexOf("total() * 2")
        val newUsage = USAGE_NEW.indexOf("sum() * 2")
        assertTrue(
            "the old declaration is framed: ${outline.navigator.shown.map { it.startOffset }}",
            outline.navigator.shown.any { it.document === shown.viewer.editor1.document && it.startOffset == oldStart },
        )
        assertTrue(
            "the new declaration is framed: ${outline.navigator.shown.map { it.startOffset }}",
            outline.navigator.shown.any { it.document === shown.viewer.editor2.document && it.startOffset == newStart },
        )
        assertTrue("the old usage is framed", outline.navigator.shown.any { it.document === shown.viewer.editor1.document && it.startOffset == oldUsage })
        assertTrue("the new usage is framed", outline.navigator.shown.any { it.document === shown.viewer.editor2.document && it.startOffset == newUsage })

        val highs = outline.usageHighlights.shown
        assertEquals("one usage is highlighted on each side", 2, highs.size)
        assertEquals(
            "the usage has the first palette colour",
            setOf(RelationshipPalette.colour(0)),
            highs.map { it.getTextAttributes(null)?.backgroundColor }.toSet(),
        )
        assertTrue("the palette stays above the navigator's usage detail", highs.all { it.layer >= HighlighterLayer.SELECTION })
        assertTrue("both editors have the usage colour", highs.any { it.document === shown.viewer.editor1.document } && highs.any { it.document === shown.viewer.editor2.document })

        outline.setOutlineView(OutlineView.CONTAINER_TREE)
        assertTrue("the container tree does not add usage colours", outline.usageHighlights.shown.isEmpty())
        assertEquals("the container tree is back", "method total → sum()  rename candidate, 1 usage", rows(outline).single { it.contains("rename candidate") }.trimStart())
    }

    fun `test relationships 06 R13 R14 the change filter and the kind filter work in the refactorings view`() {
        // relationships/06-refactorings-view R13, R14
        val local = myFixture.addFileToProject("local/Order.java", USAGE_NEW).virtualFile
        val shown = open(SimpleDiffRequest("usage", DiffContents.vcsRevision(project, USAGE_OLD, "/repo/Order.java"), DiffContents.localFile(project, local), "HEAD", "Current"))
        val outline = shown.outline
        awaitResult(outline) { state -> labels(state.items).any { it.contains("usages renamed") } }
        outline.setOutlineView(OutlineView.REFACTORINGS)

        outline.setHidden(ChangeType.CHANGED, true)

        assertTrue("a relationship has no change type: ${rows(outline)}", rows(outline).any { it.trimStart() == "rename total → sum, 1 usage  high" })
        assertTrue("a grouped usage is never hidden", rows(outline).any { it.contains("method twice()  usages renamed") })

        outline.setHidden(ChangeType.CHANGED, false)
        outline.setHiddenRelationshipKind(RelationshipKind.RENAME, true)

        assertFalse("the hidden rename row is gone", rows(outline).any { it.trimStart().startsWith("rename total") })
        assertTrue("the hidden relationship leaves its declaration in Other changes", rows(outline).any { it.contains("method total → sum()  rename candidate, 1 usage") })
        assertTrue("the grouped usage stays", rows(outline).any { it.contains("method twice()  usages renamed") })

        outline.setHiddenRelationshipKind(RelationshipKind.RENAME, false)
        assertTrue(rows(outline).any { it.trimStart() == "rename total → sum, 1 usage  high" })
    }

    fun `test relationships 06 R15 a hidden relationship never hides a changed declaration`() {
        // relationships/06-refactorings-view R15
        val old = method("total")
        val new = method("sum")
        val engine = ResultEngine().apply {
            status = Status.ANALYZED
            changes = listOf(
                ChangeNode(
                    new, TextRange(0, 5), TextRange(0, 3), Presence.MATCHED,
                    content = Content.CHANGED, renamedFrom = old,
                ),
            )
            relationships = listOf(
                Relationship(
                    java.util.EnumSet.of(RelationshipKind.RENAME),
                    listOf(RelationshipEndpoint(old, TextRange(0, 5))),
                    listOf(RelationshipEndpoint(new, TextRange(0, 3))),
                    Confidence.HIGH,
                    listOf(Evidence.Name("total", "sum")),
                ),
            )
        }
        maskEngines(engine)
        val outline = open(history(JAVA_OLD, JAVA_NEW, "Greeter.java")).awaitResult()
        outline.setOutlineView(OutlineView.REFACTORINGS)

        outline.setHiddenRelationshipKind(RelationshipKind.RENAME, true)

        assertTrue("the changed declaration is still shown", rows(outline).any { it.contains("method total → sum()") })
    }

    private fun method(name: String) = Subject.Declaration(DeclarationKind.METHOD, name, "")

    private fun labels(items: List<OutlineItem>): List<String> = items.flatMap { listOf(it.label) + labels(it.children) }

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

    /** Selects the first row matching [predicate] and returns it. */
    private fun selectItem(outline: OutlineController, predicate: (OutlineItem) -> Boolean): OutlineItem {
        val root = outline.panel.tree.model.root as DefaultMutableTreeNode
        val node = root.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }
            .first { (it.userObject as? OutlineItem)?.let(predicate) == true }
        outline.panel.tree.selectionPath = TreePath(node.path)
        return node.userObject as OutlineItem
    }

    /** Gives [editor] a viewport small enough for a scroll to leave a frame, as a diff window does. */
    private fun show(editor: Editor) {
        EditorTestUtil.setEditorVisibleSize(editor, 100, 14)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun assertHighlightedAt(editor: Editor, offset: Int) {
        assertTrue("offset $offset not found in the text", offset >= 0)
        val starts = editor.markupModel.allHighlighters.map { it.startOffset }
        assertTrue("no highlighter starts at $offset; highlighters start at $starts", offset in starts)
    }

    private fun factory(engine: ComparisonEngine) = object : ComparisonEngineFactory {
        override fun create(project: Project?): ComparisonEngine = engine
    }

    private fun maskEngines(vararg engines: ComparisonEngine) {
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, engines.map(::factory), testRootDisposable)
    }

    /** Returns the set status and reason for every file, so each outline fallback can be shown without a real engine. */
    private class ResultEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.result")
        override val displayName = "Result (test)"
        override val version = "1"

        @Volatile
        var status = Status.FAILED

        @Volatile
        var reason: String? = null

        @Volatile
        var changes: List<ChangeNode> = emptyList()

        @Volatile
        var unanalyzed: List<RangePair> = emptyList()

        @Volatile
        var relationships: List<Relationship> = emptyList()

        override fun supports(languageId: String) = true

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult =
            ComparisonResult(id, version, status, changes = changes, unanalyzed = unanalyzed, fallbackReason = reason, relationships = relationships)
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

    /** Waits until [finish] is called, then reports a failed analysis, so a fallback arrives after the user minimises. */
    private class DelayedFallbackEngine : ComparisonEngine {
        override val id = EngineId("smartdiff.test.delayedFallback")
        override val displayName = "Delayed fallback (test)"
        override val version = "1"

        @Volatile
        private var finished = false

        fun finish() {
            finished = true
        }

        override fun supports(languageId: String) = true

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
            while (!finished) {
                cancellation.checkCanceled()
                Thread.sleep(5)
            }
            return ComparisonResult(id, version, Status.FAILED, fallbackReason = "analysis failed after a delay")
        }
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
        /** A declaration that moves far up the new side, so the native sync would map its frames apart. */
        val MOVED_OLD: String = buildString {
            append("class Moved {\n")
            repeat(60) { append("    int before$it() { return $it; }\n") }
            append(MOVED_METHOD)
            repeat(60) { append("    int after$it() { return $it; }\n") }
            append("}\n")
        }
        val MOVED_NEW: String = buildString {
            append("class Moved {\n")
            repeat(20) { append("    int before$it() { return $it; }\n") }
            append(MOVED_METHOD)
            repeat(40) { append("    int before${it + 20}() { return ${it + 20}; }\n") }
            repeat(60) { append("    int after$it() { return $it; }\n") }
            append("}\n")
        }
        const val MOVED_METHOD = "    int moved() {\n        int value = 1;\n        return value;\n    }\n"

        const val JAVA_OLD = "class Greeter {\n    String hello() { return \"hi\"; }\n}\n"
        const val JAVA_NEW = "class Greeter {\n    String hello() { return \"hello\"; }\n\n    void bye() {}\n}\n"
        const val FORMATTED_OLD = "class Counter {\n    int a() { return 1; }\n\n    int b() { return 2; }\n}\n"
        const val FORMATTED_NEW = "class Counter {\n    int a() { return 3; }\n\n    int b() {\n        return 2;\n    }\n}\n"
        const val USAGE_OLD = "class Order {\n    int total() { return 2; }\n    int twice() { return total() * 2; }\n}\n"
        const val USAGE_NEW = "class Order {\n    int sum() { return 2; }\n    int twice() { return sum() * 2; }\n}\n"
        const val KOTLIN_OLD = "class Greeter {\n    fun hello() = \"hi\"\n}\n"
        const val KOTLIN_NEW = "class Greeter {\n    fun hello() = \"hello\"\n\n    fun bye() {}\n}\n"
    }
}
