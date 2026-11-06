package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.BooleanGetter
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.NoneChangesGroupingFactory
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcsUtil.VcsUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import io.github.agadek.smartdiff.ui.ChangeType
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import java.util.concurrent.atomic.AtomicInteger

/** Hide and filter in the Changes tree: actions, toggles, filter and summary (changes-tree/04). */
class ChangesTreeFilterTest : BasePlatformTestCase() {

    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        saved = settings.state
        settings.loadState(SmartDiffSettings.SettingsState())
        ChangesSummaryService.getInstance(project).run {
            waitForSummaries()
            clear()
        }
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

    fun `test the view options group has the smart diff toggles and filter`() {
        // changes-tree/04-hide-and-filter R1
        ChangesTreeActions.installNow()
        val manager = ActionManager.getInstance()
        val actions = manager.getAction(ChangesTreeActions.GROUP_ID) as DefaultActionGroup
        val viewOptions = manager.getAction(ChangesTreeActions.VIEW_OPTIONS_GROUP_ID) as? DefaultActionGroup
        val toolbar = manager.getAction(ChangesTreeActions.TOOLBAR_GROUP_ID) as? DefaultActionGroup
        val parent = listOfNotNull(viewOptions, toolbar).firstOrNull { it.containsAction(actions) }

        assertNotNull("the SMART Diff actions are in View Options or in the Changes toolbar", parent)
        val children = actions.getChildren(manager).toList()
        assertTrue(
            "the toggles and the filter live in one group",
            children.contains(manager.getAction("SmartDiff.ChangesTree.StructuralSummary")) &&
                children.contains(manager.getAction("SmartDiff.ChangesTree.Declarations")) &&
                children.contains(manager.getAction("SmartDiff.ChangesTree.HideChangeTypes")),
        )
        if (viewOptions != null && viewOptions.containsAction(actions)) {
            assertFalse("the preferred View Options placement is used alone", toolbar?.containsAction(actions) == true)
        }
    }

    fun `test the toolbar is the fallback when the view options group is absent`() {
        // changes-tree/04-hide-and-filter R1
        val group = DefaultActionGroup()
        val toolbar = DefaultActionGroup()

        assertTrue("the toolbar gets the group when View Options is absent", ChangesTreeActions.place(group, null, toolbar))
        assertTrue(toolbar.containsAction(group))

        val viewOptions = DefaultActionGroup()
        val otherToolbar = DefaultActionGroup()
        assertTrue("View Options is preferred when it exists", ChangesTreeActions.place(group, viewOptions, otherToolbar))
        assertTrue(viewOptions.containsAction(group))
        assertFalse(otherToolbar.containsAction(group))
    }

    fun `test the summary and declaration toggles are independent`() {
        // changes-tree/04-hide-and-filter R2
        val engine = ScriptedEngine(cartResult())
        maskEngines(engine)
        val change = modified("Cart.java")
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()

        settings.showChangesSummary = true
        settings.showChangesDeclarations = true
        val both = summaryNode(change)!!
        assertTrue("the summary is shown", both.summary.isNotEmpty())
        assertTrue("the declaration rows are shown", both.childCount > 0)

        settings.showChangesSummary = true
        settings.showChangesDeclarations = false
        val summaryOnly = summaryNode(change)!!
        assertTrue("the summary stays with declarations off", summaryOnly.summary.isNotEmpty())
        assertEquals("declarations off leaves the file a leaf", 0, summaryOnly.childCount)
        assertTrue(summaryOnly.isLeaf)

        settings.showChangesSummary = false
        settings.showChangesDeclarations = true
        val declarationsOnly = summaryNode(change)!!
        assertEquals("Structural Summary off hides the grey text", "", declarationsOnly.summary)
        assertTrue("the declaration rows stay with Structural Summary off", declarationsOnly.childCount > 0)

        settings.showChangesSummary = false
        settings.showChangesDeclarations = false
        val neither = summaryNode(change)
        assertNull("both off leaves the platform's row", neither)
    }

    fun `test the structural summary toggle hides the grey text and keeps the rows`() {
        // changes-tree/04-hide-and-filter R3
        maskEngines(ScriptedEngine(cartResult()))
        val change = modified("Cart.java")
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        settings.showChangesSummary = false
        settings.showChangesDeclarations = true
        val node = summaryNode(change)!!

        val renderer = ChangesBrowserNodeRenderer(project, BooleanGetter { false }, false)
        node.render(renderer, false, false, false)
        val text = renderer.getCharSequence(false).toString()

        assertFalse("the summary or reason text is hidden", text.contains("changed"))
        assertTrue("the declaration rows are kept", node.childCount > 0)
    }

    fun `test declarations off makes each file a leaf and leaves the row to the platform`() {
        // changes-tree/04-hide-and-filter R4
        maskEngines(ScriptedEngine(cartResult()))
        val change = modified("Cart.java")
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        settings.showChangesDeclarations = false
        val (root, nodes) = tree(listOf(change))
        val node = nodes.single()

        assertTrue("the file is a leaf", node.isLeaf)
        assertEquals("the file has no declaration rows", 0, node.childCount)
        assertFalse("the declaration action leaves the file row to the platform", DeclarationNodeAction(project).handleDoubleClick(node))
        assertEquals("the file is still the row's change", listOf(change), VcsTreeModelData.allUnder(root).userObjects())
    }

    fun `test a tree toggle writes the same setting as the settings page`() {
        // changes-tree/04-hide-and-filter R5
        settings.loadState(SmartDiffSettings.SettingsState())
        val summary = ChangesTreeStructuralSummaryAction()
        val declarations = ChangesTreeDeclarationsAction()
        val event = AnActionEvent.createFromAnAction(summary, null, "test", DataContext.EMPTY_CONTEXT)

        summary.setSelected(event, false)
        declarations.setSelected(AnActionEvent.createFromAnAction(declarations, null, "test", DataContext.EMPTY_CONTEXT), false)

        assertFalse("the summary toggle writes the shared setting", settings.showChangesSummary)
        assertFalse("the declarations toggle writes the shared setting", settings.showChangesDeclarations)
        assertFalse(settings.state.showChangesSummary)
        assertFalse(settings.state.showChangesDeclarations)
    }

    fun `test toggling rebuilds without another comparison`() {
        // changes-tree/04-hide-and-filter R6
        val engine = ScriptedEngine(cartResult())
        maskEngines(engine)
        val change = modified("Cart.java")
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val listener = ChangesSummarySettingsListener()
        listener.settingsChanged()
        val calls = engine.calls.get()
        assertEquals("the file was compared once", 1, calls)

        settings.showChangesSummary = false
        settings.showChangesDeclarations = false
        listener.settingsChanged()
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()

        assertEquals("a display toggle does not compare again", calls, engine.calls.get())
    }

    fun `test an analysis setting still compares again`() {
        // changes-tree/04-hide-and-filter R6: the cache is kept only for display changes
        val engine = ScriptedEngine(cartResult())
        maskEngines(engine)
        val change = modified("Cart.java")
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val listener = ChangesSummarySettingsListener()
        listener.settingsChanged()
        val calls = engine.calls.get()

        settings.limits = Limits.DEFAULT.copy(timeoutMillis = Limits.DEFAULT.timeoutMillis - 1)
        listener.settingsChanged()
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()

        assertTrue("an analysis setting makes a new summary", engine.calls.get() > calls)
    }

    fun `test the filter is a hide change types submenu`() {
        // changes-tree/04-hide-and-filter R12
        ChangesTreeActions.installNow()
        val manager = ActionManager.getInstance()
        val actions = manager.getAction(ChangesTreeActions.GROUP_ID) as DefaultActionGroup
        val filter = manager.getAction("SmartDiff.ChangesTree.HideChangeTypes") as DefaultActionGroup
        val labels = filter.getChildren(manager).map { it.templatePresentation.text }

        assertEquals("one toggle per change type", ChangeType.entries.map { it.text }, labels)
        assertTrue("the filter shares the toggles' group", actions.getChildren(manager).toList().contains(filter))
        val viewOptions = manager.getAction(ChangesTreeActions.VIEW_OPTIONS_GROUP_ID) as? DefaultActionGroup
        if (viewOptions != null && viewOptions.containsAction(actions)) {
            assertTrue("the submenu is in the View Options menu", viewOptions.containsAction(actions))
        }
    }

    fun `test the tree summary counts hidden changes and ends with the hidden count`() {
        // changes-tree/04-hide-and-filter R13
        maskEngines(ScriptedEngine(cartResult()))
        val change = modified("Cart.java")
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.ADDED, ChangeType.MOVED)

        val node = summaryNode(change)!!

        assertEquals("1 changed · 1 added · 1 moved · 1 formatting-only · 2 hidden", node.summary)
    }

    fun `test a shown change with a hidden child stays with its own labels in the tree`() {
        // changes-tree/04-hide-and-filter R7, R9
        maskEngines(ScriptedEngine(parserResult()))
        val change = modified("Parser.java")
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.ADDED)
        val node = tree(listOf(change)).second.single() as SummaryChangeNode

        assertEquals("1 changed · 1 added · 1 hidden", node.summary)
        val rows = node.preorderEnumeration().toList().filterIsInstance<DeclarationNode>()
        assertEquals(listOf("class Parser  changed"), rows.map { it.item.label })
        assertTrue("the hidden added method is not in the tree", rows.none { it.item.title == "method added()" })
    }

    fun `test a file whose changes are all hidden stays with all hidden in its summary and no rows`() {
        // changes-tree/04-hide-and-filter R14
        maskEngines(ScriptedEngine(cartResult()))
        val change = modified("Cart.java")
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        settings.hiddenChangesTreeChangeTypes = ChangeType.entries.toSet()
        val (root, nodes) = tree(listOf(change))
        val node = nodes.single() as SummaryChangeNode

        assertTrue("the file stays in the tree", change in VcsTreeModelData.allUnder(root).userObjects())
        assertTrue("the file has no declaration rows", node.isLeaf)
        assertEquals("the file has no declaration rows", 0, node.childCount)
        assertEquals("1 changed · 1 added · 1 moved · 1 formatting-only · all hidden", node.summary)
    }

    fun `test speed search text of a declaration row is its label`() {
        // changes-tree/04-hide-and-filter R15
        maskEngines(ScriptedEngine(cartResult()))
        val change = modified("Cart.java")
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.ADDED)
        val node = summaryNode(change)!!
        val rows = node.preorderEnumeration().toList().filterIsInstance<DeclarationNode>()

        val changed = rows.single { it.item.title == "method total()" }
        assertEquals("method total()  changed", changed.textPresentation)
        assertTrue("a hidden row is not in the tree", rows.none { it.item.title == "method isEmpty()" })

        settings.showChangesDeclarations = false
        val withoutRows = summaryNode(change)!!
        assertTrue("declarations off has no row to find", withoutRows.preorderEnumeration().toList().none { it is DeclarationNode })
    }

    fun `test changing the tree filter leaves the outline filter unchanged`() {
        // changes-tree/04-hide-and-filter R16
        settings.loadState(SmartDiffSettings.SettingsState())
        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.MOVED)

        assertEquals("the outline still shows moved changes", emptySet<ChangeType>(), settings.hiddenChangeTypes)

        settings.hiddenChangeTypes = setOf(ChangeType.ADDED)
        assertEquals("the tree still hides moved changes", setOf(ChangeType.MOVED), settings.hiddenChangesTreeChangeTypes)
    }

    fun `test the changes tree filter is stored separately and starts empty`() {
        // changes-tree/04-hide-and-filter R17
        settings.loadState(SmartDiffSettings.SettingsState())
        assertEquals(emptySet<ChangeType>(), settings.hiddenChangesTreeChangeTypes)

        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.REMOVED)
        assertEquals("REMOVED", settings.state.hiddenChangesTreeChangeTypes)
        assertEquals("the outline value is untouched", "", settings.state.hiddenChangeTypes)
    }

    fun `test changing the filter rebuilds without another comparison`() {
        // changes-tree/04-hide-and-filter R18
        val engine = ScriptedEngine(cartResult())
        maskEngines(engine)
        val change = modified("Cart.java")
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val listener = ChangesSummarySettingsListener()
        listener.settingsChanged()
        val calls = engine.calls.get()

        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.ADDED)
        listener.settingsChanged()
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()

        assertEquals("a filter change does not compare again", calls, engine.calls.get())
    }

    private fun maskEngines(vararg engines: ComparisonEngine) {
        ExtensionTestUtil.maskExtensions(ComparisonEngineFactory.EP_NAME, engines.map(::factory), testRootDisposable)
    }

    private fun factory(engine: ComparisonEngine) = object : ComparisonEngineFactory {
        override fun create(project: Project?): ComparisonEngine = engine
    }

    private fun modified(fileName: String): Change {
        val path = VcsUtil.getFilePath("/repo/$fileName", false)
        return Change(SimpleContentRevision(OLD_CART, path, "1"), SimpleContentRevision(NEW_CART, path, "2"))
    }

    private fun tree(changes: List<Change>): Pair<ChangesBrowserNode<*>, List<ChangesBrowserChangeNode>> {
        val builder = TreeModelBuilder(project, NoneChangesGroupingFactory).setChanges(changes, null)
        ChangesSummaryModifier(project).modifyTreeModelBuilder(builder)
        val nodes = builder.myRoot.depthFirstEnumeration().toList().filterIsInstance<ChangesBrowserChangeNode>()
        builder.build()
        return builder.myRoot to nodes
    }

    private fun summaryNode(change: Change, changes: List<Change> = listOf(change)): SummaryChangeNode? =
        tree(changes).second.filterIsInstance<SummaryChangeNode>().firstOrNull { it.userObject === change }

    private class ScriptedEngine(private val result: ComparisonResult) : ComparisonEngine {
        override val id: EngineId = result.engineId
        override val displayName = "Scripted (test)"
        override val version = "1"
        val calls = AtomicInteger()

        override fun supports(languageId: String) = true

        override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
            calls.incrementAndGet()
            return result
        }
    }

    private companion object {
        val ENGINE_ID = EngineId("smartdiff.test.changes-tree-filter")

        val OLD_CART = "class Cart { int total() { return 1; } }"
        val NEW_CART = "class Cart { int total() { return 2; } boolean isEmpty() { return false; } }"

        fun method(name: String, old: TextRange?, new: TextRange?, presence: Presence = Presence.MATCHED, moved: Boolean = false, content: Content = Content.UNCHANGED) =
            ChangeNode(Subject.Declaration(DeclarationKind.METHOD, name, ""), old, new, presence, moved = moved, content = content)

        /** One changed, one added, one moved and one formatting-only method under an unchanged class. */
        fun cartResult() = ComparisonResult(
            ENGINE_ID,
            "1",
            Status.ANALYZED,
            changes = listOf(
                ChangeNode(
                    Subject.Declaration(DeclarationKind.CLASS, "Cart", null),
                    TextRange(0, 60),
                    TextRange(0, 80),
                    Presence.MATCHED,
                    children = listOf(
                        method("total", TextRange(0, 10), TextRange(0, 10), content = Content.CHANGED),
                        method("isEmpty", null, TextRange(11, 20), presence = Presence.ADDED),
                        method("format", TextRange(21, 30), TextRange(21, 30), content = Content.FORMATTING_ONLY),
                        method("moved", TextRange(31, 40), TextRange(41, 50), moved = true),
                    ),
                ),
            ),
        )

        /** A changed class with an added method, so a shown change has a hidden child. */
        fun parserResult() = ComparisonResult(
            ENGINE_ID,
            "1",
            Status.ANALYZED,
            changes = listOf(
                ChangeNode(
                    Subject.Declaration(DeclarationKind.CLASS, "Parser", null),
                    TextRange(0, 20),
                    TextRange(0, 30),
                    Presence.MATCHED,
                    content = Content.CHANGED,
                    children = listOf(method("added", null, TextRange(21, 30), presence = Presence.ADDED)),
                ),
            ),
        )
    }
}
