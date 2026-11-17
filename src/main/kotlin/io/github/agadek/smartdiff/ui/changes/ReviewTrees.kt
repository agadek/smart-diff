package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.FontUtil
import io.github.agadek.smartdiff.engine.api.ApiGuard
import io.github.agadek.smartdiff.engine.api.GuardedFeature
import io.github.agadek.smartdiff.ui.OutlinePath
import io.github.agadek.smartdiff.ui.PendingOutlineReveal
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import org.jetbrains.annotations.TestOnly
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * One kind of review tree, such as the Git log's commit details or the changes of a GitHub pull request
 * (changes-tree/07, ADR 0013). Each adapter lives in its own optional descriptor, so the plugin loads without it (R21).
 */
interface ReviewTreeAdapter {

    /** A short name for logs. */
    val name: String

    /** Whether [tree], a changes tree that was just shown, is one of this adapter's trees. Called on the EDT. */
    fun accepts(tree: ChangesTree): Boolean

    /**
     * The change that [node]'s diff compares (R4), or null when [node] is not a file node of this tree. It must not read
     * revision content: the summary executor reads it later (R16).
     */
    fun changeOf(tree: ChangesTree, node: ChangesBrowserNode<*>): Change?

    /**
     * Opens [node]'s diff from [tree] with [path] selected in the outline (R10), or without a selection when that is not
     * possible (R11). Returns whether a diff was shown.
     */
    fun open(tree: ChangesTree, node: ReviewSummaryNode, path: OutlinePath): Boolean

    companion object {
        val EP_NAME: ExtensionPointName<ReviewTreeAdapter> = ExtensionPointName.create("io.github.agadek.smartdiff.reviewTreeAdapter")
    }
}

/**
 * A file row of a review tree with its structural summary. It keeps the platform node's user object, so the tree's
 * selected changes, diff preview and actions see the same change (R14), and draws as that node does, followed by the
 * summary in grey. It starts collapsed.
 */
class ReviewSummaryNode internal constructor(
    val original: ChangesBrowserNode<*>,
    val change: Change,
    val summary: String,
) : ChangesBrowserNode<Any>(original.userObject) {

    override fun render(renderer: ChangesBrowserNodeRenderer, selected: Boolean, expanded: Boolean, hasFocus: Boolean) {
        original.render(renderer, selected, expanded, hasFocus)
        if (summary.isNotEmpty()) renderer.append(FontUtil.spaceAndThinSpace() + summary, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    override fun getTextPresentation(): String = original.textPresentation

    override fun getSortWeight(): Int = original.sortWeight

    /** Counted as a file, as the node it replaces, so the directory counts stay the platform's (R14). */
    override fun isFile(): Boolean = true

    override fun getBackgroundColor(project: Project): java.awt.Color? = original.getBackgroundColor(project)

    override fun shouldExpandByDefault(): Boolean = false

    override fun toString(): String = original.toString()
}

/**
 * The summaries of one review tree: its scope in [ChangesSummaryService] and the step that puts summaries into each new
 * model of the tree, run from the tree's `model` property before the platform restores the tree state (ADR 0013).
 */
class ReviewTreeSummaries internal constructor(
    private val project: Project,
    val tree: ChangesTree,
    val adapter: ReviewTreeAdapter,
    parent: Disposable,
) : Disposable {

    private val scope = ChangesSummaryService.getInstance(project).createScope(this, refresh = { rebuild() })
    private val failed = AtomicBoolean()
    private var applying = false

    private val modelListener = PropertyChangeListener { event ->
        (event.newValue as? DefaultTreeModel)?.let(::apply)
    }

    init {
        Disposer.register(parent, this)
        tree.putClientProperty(KEY, this)
        tree.addPropertyChangeListener(JTree.TREE_MODEL_PROPERTY, modelListener)
        tree.putClientProperty(ChangesTreeSetup.DECLARATION_OPENER, ChangesTreeSetup.DeclarationOpener { declaration ->
            val file = declaration.reviewFileNode ?: return@DeclarationOpener false
            ApiGuard.run(GuardedFeature.REVIEW_TREE_HOOK, fallback = { false }) { adapter.open(tree, file, declaration.path) }
        })
        (tree.model as? DefaultTreeModel)?.let(::apply)
    }

    /**
     * Replaces the file nodes of [model] with summary nodes and adds their declaration rows (R1 to R9, R13 to R15). A
     * node already summarized, or whose summary is not known yet, is left; arriving summaries rebuild the tree.
     */
    fun apply(model: DefaultTreeModel) {
        if (applying || failed.get() || scope.isDisposed) return
        // platform/01 R10, R14: a missing or changed tree API turns review tree summaries off and leaves the tree as is.
        ApiGuard.run(GuardedFeature.REVIEW_TREE_HOOK, fallback = {}) { applyGuarded(model) }
    }

    private fun applyGuarded(model: DefaultTreeModel) {
        applying = true
        try {
            val changed = applyTo(model.root as? ChangesBrowserNode<*> ?: return)
            if (changed) model.nodeStructureChanged(model.root as ChangesBrowserNode<*>)
            ChangesTreeSetup.configure(tree, tree.root.depthFirstEnumeration().asSequence().any { it is DeclarationNode })
        } catch (e: Exception) {
            // A platform change the adapter does not expect leaves the tree as the platform draws it.
            if (failed.compareAndSet(false, true)) thisLogger().warn("Cannot add summaries to the ${adapter.name} tree", e)
        } finally {
            applying = false
        }
    }

    /** Puts summaries under [root]; returns whether a node was replaced. */
    internal fun applyTo(root: ChangesBrowserNode<*>): Boolean {
        val settings = SmartDiffSettings.getInstance()
        val showSummary = settings.showChangesSummary
        val showDeclarations = settings.showChangesDeclarations
        // As in the Changes tree: with both settings off nothing is compared and the platform's rows stay (R12).
        if (!showSummary && !showDeclarations) return false
        val nodes = fileNodes(root)
        val summaries = ChangesSummaryService.getInstance(project).summaries(nodes.map { it.second }, scope)
        var changed = false
        for ((pair, summary) in nodes.zip(summaries)) {
            if (summary == null) continue
            val (node, change) = pair
            val filtered = ChangesTreeFilter.filter(summary.items, settings.hiddenChangesTreeChangeTypes)
            val rows = if (showDeclarations) filtered.items else emptyList()
            if (!showSummary && rows.isEmpty()) continue
            val parent = node.parent as? ChangesBrowserNode<*> ?: continue
            val index = parent.getIndex(node)
            val text = if (showSummary) ChangesTreeFilter.summary(summary.text, filtered) else ""
            parent.remove(index)
            parent.insert(ReviewSummaryNode(node, change, text).apply { rows.forEach { add(DeclarationNode(it)) } }, index)
            changed = true
        }
        return changed
    }

    /** The first [ChangesSummaryModifier.MAX_FILES] file nodes in depth-first order, with their changes (R15). */
    private fun fileNodes(root: ChangesBrowserNode<*>): List<Pair<ChangesBrowserNode<*>, Change>> {
        val nodes = mutableListOf<Pair<ChangesBrowserNode<*>, Change>>()
        val all = root.depthFirstEnumeration()
        while (all.hasMoreElements() && nodes.size < ChangesSummaryModifier.MAX_FILES) {
            val node = all.nextElement() as? ChangesBrowserNode<*> ?: continue
            if (node is ReviewSummaryNode || node is DeclarationNode || !node.isLeaf) continue
            val change = adapter.changeOf(tree, node) ?: continue
            nodes += node to change
        }
        return nodes
    }

    /** Asks the platform to rebuild the tree, which runs [apply] on the new model. */
    fun rebuild() {
        ApplicationManager.getApplication().invokeLater({
            if (!scope.isDisposed && !project.isDisposed) tree.rebuildTree()
        }, ModalityState.any())
    }

    override fun dispose() {
        ApplicationManager.getApplication().serviceIfCreated<ReviewTreeWatcher>()?.forget(tree, this)
        tree.removePropertyChangeListener(JTree.TREE_MODEL_PROPERTY, modelListener)
        if (tree.getClientProperty(KEY) === this) tree.putClientProperty(KEY, null)
        tree.putClientProperty(ChangesTreeSetup.DECLARATION_OPENER, null)
    }

    companion object {
        internal val KEY = Key.create<ReviewTreeSummaries>("smartdiff.review.tree.summaries")

        /** The summaries attached to [tree], if any. */
        fun of(tree: JTree): ReviewTreeSummaries? = tree.getClientProperty(KEY) as? ReviewTreeSummaries
    }
}

/** The file row a declaration row of a review tree is under. */
val DeclarationNode.reviewFileNode: ReviewSummaryNode?
    get() = generateSequence(parent) { it.parent }.firstNotNullOfOrNull { it as? ReviewSummaryNode }

/**
 * Finds review trees as they are shown and attaches [ReviewTreeSummaries] to the ones an adapter accepts. One AWT
 * hierarchy listener sees every changes tree when it is first shown and when it is removed (ADR 0013).
 */
@Service(Service.Level.APP)
class ReviewTreeWatcher : Disposable {

    private val installed = AtomicBoolean()
    private val trees = java.util.concurrent.ConcurrentHashMap<ChangesTree, ReviewTreeSummaries>()

    private val listener = AWTEventListener { event ->
        val e = event as? HierarchyEvent ?: return@AWTEventListener
        val tree = e.component as? ChangesTree ?: return@AWTEventListener
        when {
            e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && tree.isShowing -> attach(tree)
            e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && !tree.isDisplayable -> detach(tree)
        }
    }

    /** Starts watching, once per application, when any review tree adapter is installed. */
    fun install() {
        if (ReviewTreeAdapter.EP_NAME.extensionList.isEmpty()) return
        if (!installed.compareAndSet(false, true)) return
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.HIERARCHY_EVENT_MASK)
    }

    /** Attaches summaries to [tree] if an adapter accepts it and it has none yet. */
    fun attach(tree: ChangesTree): ReviewTreeSummaries? =
        ApiGuard.run(GuardedFeature.REVIEW_TREE_HOOK, fallback = { null }) { attachGuarded(tree) }

    private fun attachGuarded(tree: ChangesTree): ReviewTreeSummaries? {
        trees[tree]?.let { return it }
        val project = tree.project
        if (project.isDisposed || ReviewTreeSummaries.of(tree) != null) return null
        val adapter = ReviewTreeAdapter.EP_NAME.extensionList.firstOrNull { accepts(it, tree) } ?: return null
        val summaries = ReviewTreeSummaries(project, tree, adapter, project)
        trees[tree] = summaries
        return summaries
    }

    private fun accepts(adapter: ReviewTreeAdapter, tree: ChangesTree): Boolean = try {
        adapter.accepts(tree)
    } catch (e: Exception) {
        thisLogger().warn("Cannot check the ${adapter.name} tree", e)
        false
    }

    /** Disposes the summaries of a removed [tree] and its scope. */
    fun detach(tree: ChangesTree) {
        trees.remove(tree)?.let(Disposer::dispose)
    }

    /** Drops [tree] once its [summaries] are disposed, for example with its project. */
    internal fun forget(tree: ChangesTree, summaries: ReviewTreeSummaries) {
        trees.remove(tree, summaries)
    }

    /** Every attached review tree of [project]. */
    fun trees(project: Project): List<ReviewTreeSummaries> = trees.values.filter { it.tree.project === project }

    /** Rebuilds every review tree of [project], after a settings change (R12, R13). */
    fun rebuildAll(project: Project) = trees(project).forEach { it.rebuild() }

    @TestOnly
    internal fun detachAll() = trees.keys.toList().forEach(::detach)

    override fun dispose() {
        if (installed.get()) Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        trees.values.toList().forEach(Disposer::dispose)
        trees.clear()
    }

    companion object {
        fun getInstance(): ReviewTreeWatcher = service()

        /** Whether [component] is inside the tool window [id] of [project]. */
        fun isInToolWindow(project: Project, component: Component, id: String): Boolean {
            val window = ToolWindowManager.getInstance(project).getToolWindow(id) ?: return false
            return SwingUtilities.isDescendingFrom(component, window.component)
        }

        /** Whether one of [component]'s ancestors is of a class in a package starting with [prefix]. */
        fun hasAncestorInPackage(component: Component, prefix: String): Boolean =
            generateSequence(component.parent) { it.parent }.any { it.javaClass.name.startsWith(prefix) }

        /** Whether one of [component]'s ancestors is, or extends, the class named [className]. */
        fun hasAncestorOfClass(component: Component, className: String): Boolean =
            generateSequence(component.parent) { it.parent }.any { ancestor ->
                generateSequence<Class<*>>(ancestor.javaClass) { it.superclass }.any { it.name == className }
            }
    }
}

/** Starts the [ReviewTreeWatcher] once a project opens. */
class ReviewTreeStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater({ ReviewTreeWatcher.getInstance().install() }, ModalityState.any())
    }
}

/**
 * Opens a review tree's file row through the tree's own Enter handler, as a click on the row would, after recording
 * [path] for the outline of the diff it opens. Without a handler, the file's own diff opens without a selection (R11).
 */
internal fun openThroughTree(
    tree: ChangesTree,
    node: ReviewSummaryNode,
    path: OutlinePath,
    fallback: (Project, Change) -> Boolean = { project, change -> ChangesDiff.show(project, listOf(change), 0) },
): Boolean {
    val project = tree.project
    val file = (node.change.afterRevision ?: node.change.beforeRevision)?.file
    val fileName = file?.name
    tree.selectionPath = TreePath(node.path)
    val handler = tree.enterKeyHandler
    if (handler != null && file != null && fileName != null) {
        PendingOutlineReveal.getInstance(project).put(file.path, fileName, path)
        val enter = java.awt.event.KeyEvent(
            tree, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
            java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.CHAR_UNDEFINED,
        )
        if (handler.process(enter)) return true
        PendingOutlineReveal.getInstance(project).take(file.path, fileName)
    }
    return fallback(project, node.change)
}
