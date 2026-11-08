package io.github.agadek.smartdiff.ui.changes

import com.intellij.diff.FrameDiffTool
import com.intellij.diff.merge.MergeTool
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.ide.DataManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesViewModifier
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import io.github.agadek.smartdiff.ui.ItemKind
import io.github.agadek.smartdiff.ui.OutlineItem
import io.github.agadek.smartdiff.ui.OutlinePath
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import org.jetbrains.annotations.TestOnly
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.event.TreeSelectionEvent
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

/**
 * Follows the caret in an editor into the Local Changes and Commit tree (changes-tree/05): the file's row expands and
 * the innermost changed declaration under the caret is selected and scrolled into view, or the file row when the caret
 * is outside the changed declarations. It reads the cached summary only, never moves focus, opens a diff or changes the
 * commit, and debounces caret moves so typing and held arrow keys stay smooth.
 */
@Service(Service.Level.PROJECT)
class ChangesTreeCaretFollower internal constructor(
    private val project: Project,
    private val treeProvider: () -> ChangesListView?,
    private val summaries: ChangesSummaryService,
    private val focusOwner: () -> Component?,
    private val treeShowing: (ChangesListView) -> Boolean = { it.isShowing },
    private val viewerOf: (Editor) -> FrameDiffTool.DiffViewer? = ::viewerFromContext,
    private val mergeViewerOf: (Editor) -> MergeTool.MergeViewer? = ::mergeViewerFromContext,
    private val logFailure: (Throwable) -> Unit = ::logFollowFailure,
) : Disposable {

    constructor(project: Project) : this(
        project,
        { liveTree(project) },
        ChangesSummaryService.getInstance(project),
        { KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner },
    )

    private enum class Side { OLD, NEW }

    private data class SideFile(val side: Side, val file: VirtualFile, val fallback: Boolean)

    private data class PendingFollow(
        val editor: Editor,
        val offset: Int,
        val epoch: Long,
        val missingRowTried: Boolean = false,
        val waitingForSummary: Boolean = false,
    )

    private sealed interface Target {
        data class FileRow(val row: ChangesBrowserChangeNode, val retry: Boolean) : Target

        data class Declaration(val fileRow: SummaryChangeNode, val path: OutlinePath) : Target

        data object Stale : Target

        data object None : Target
    }

    @Volatile
    private var started = false

    /** Set before the queued cleanup, so a queued [start] cannot revive a disposed follower. */
    @Volatile
    private var disposed = false

    private var watchedTree: ChangesListView? = null
    private var watchedModel: TreeModel? = null
    private var following = 0
    private val selectionEpoch = AtomicLong()

    /** Only touched on the EDT; [dispose] queues its cleanup when another thread calls it. */
    private var pending: PendingFollow? = null
    private var timer: Timer? = null
    private var failureLogged = false

    /** How many times the debounce timer ran a follow, for the debounce test. */
    @TestOnly
    internal var followRuns = 0

    /** Whether a retry is still remembered, for the tests that check it is cleared. */
    @TestOnly
    internal fun hasPendingFollow(): Boolean = pending != null

    /** Starts listening once on the EDT; safe to call more than once. The project activity creates the service at project open. */
    fun start() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { start() }
            return
        }
        if (started || disposed || project.isDisposed) return
        started = true
        EditorFactory.getInstance().eventMulticaster.addCaretListener(caretListener, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, selectionChangedListener)
        project.messageBus.connect(this).subscribe(ChangesViewModifier.TOPIC, ChangesViewModifier.ChangesViewModifierListener {
            onEdt { if (pending != null) restartTimer() }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SmartDiffSettings.CHANGED, SmartDiffSettings.Listener {
            onEdt {
                if (!SmartDiffSettings.getInstance().followChangesTreeCaret) cancelPending() else syncTree()
            }
        })
        syncTree()
    }

    override fun dispose() {
        started = false
        disposed = true
        val cleanup = {
            timer?.stop()
            pending = null
            detachTree()
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            cleanup()
        } else {
            ApplicationManager.getApplication().invokeLater(cleanup)
        }
    }

    /** Runs the same mapping and selection path as the debounce timer, without waiting, for tests. */
    @TestOnly
    internal fun followNow(editor: Editor, offset: Int) {
        val run = {
            pending = PendingFollow(editor, offset, selectionEpoch.get(), missingRowTried = true)
            runPending()
        }
        if (ApplicationManager.getApplication().isDispatchThread) run() else ApplicationManager.getApplication().invokeAndWait(run)
    }

    /** Drains a queued debounce or model retry once, for tests. */
    @TestOnly
    internal fun waitForPendingFollow() {
        val drain = {
            timer?.stop()
            if (pending != null) runPending()
        }
        if (ApplicationManager.getApplication().isDispatchThread) drain() else ApplicationManager.getApplication().invokeAndWait(drain)
    }

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            val caret = event.caret ?: return
            onCaretMoved(event.editor, caret.offset)
        }
    }

    private val selectionChangedListener = object : FileEditorManagerListener {
        override fun selectionChanged(event: FileEditorManagerEvent) {
            val editor = event.manager.selectedTextEditor ?: return
            onCaretMoved(editor, editor.caretModel.offset)
        }
    }

    private val modelListener = object : TreeModelListener {
        override fun treeNodesChanged(event: TreeModelEvent) = modelChanged()
        override fun treeNodesInserted(event: TreeModelEvent) = modelChanged()
        override fun treeNodesRemoved(event: TreeModelEvent) = modelChanged()
        override fun treeStructureChanged(event: TreeModelEvent) = modelChanged()
    }

    private val modelPropertyListener = PropertyChangeListener { event: PropertyChangeEvent ->
        if (event.propertyName != "model") return@PropertyChangeListener
        watchedModel?.removeTreeModelListener(modelListener)
        watchedModel = watchedTree?.model
        watchedModel?.addTreeModelListener(modelListener)
        if (pending != null) restartTimer()
    }

    private val selectionListener = TreeSelectionListener { _: TreeSelectionEvent ->
        // The follower's own selection sets `following`; a selection the user makes in the tree wins over a late caret.
        if (following == 0 && isTreeFocused()) cancelPending()
    }

    private val mouseListener = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = cancelPending()
    }

    private val keyListener = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) = cancelPending()
    }

    internal fun onCaretMoved(editor: Editor, offset: Int) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { onCaretMoved(editor, offset) }
            return
        }
        if (!started || project.isDisposed || editor.project !== project || editor.isDisposed) return
        if (!SmartDiffSettings.getInstance().followChangesTreeCaret) {
            cancelPending()
            return
        }
        pending = PendingFollow(editor, offset, selectionEpoch.get())
        restartTimer()
    }

    private fun onEdt(action: () -> Unit) {
        if (ApplicationManager.getApplication().isDispatchThread) action()
        else ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) action() }
    }

    private fun timer(): Timer {
        ApplicationManager.getApplication().assertIsDispatchThread()
        return timer ?: Timer(debounceDelay()) { runPending() }.apply { isRepeats = false }.also { timer = it }
    }

    private fun restartTimer() {
        if (!started || project.isDisposed) return
        val timer = timer()
        timer.delay = debounceDelay()
        timer.restart()
    }

    private fun runPending() {
        timer?.stop()
        val follow = pending ?: return
        if (!started || project.isDisposed || !SmartDiffSettings.getInstance().followChangesTreeCaret) {
            pending = null
            return
        }
        if (follow.epoch != selectionEpoch.get() || follow.editor.isDisposed) {
            pending = null
            return
        }
        val tree = try {
            syncTree()
        } catch (e: Exception) {
            if (!failureLogged) {
                failureLogged = true
                logFailure(e)
            }
            pending = null
            return
        }
        if (tree == null) {
            if (!follow.waitingForSummary) pending = null
            return
        }
        if (!treeShowing(tree)) {
            pending = null
            return
        }
        val focus = focusOwner()
        if (focus != null && SwingUtilities.isDescendingFrom(focus, tree)) {
            pending = null
            return
        }
        val focusedEditor = focus?.let(::editorContaining)
        if (focusedEditor != null && focusedEditor !== follow.editor) {
            pending = null
            return
        }
        followRuns++
        try {
            follow(follow, tree)
        } catch (e: Exception) {
            if (!failureLogged) {
                failureLogged = true
                logFailure(e)
            }
            pending = null
        }
    }

    private fun follow(pendingFollow: PendingFollow, tree: ChangesListView) {
        when (val target = map(pendingFollow.editor, pendingFollow.offset, tree)) {
            is Target.FileRow -> {
                if (tree.isModelUpdateInProgress) {
                    pending = pendingFollow
                    return
                }
                selectFile(tree, target.row)
                pending = if (target.retry) {
                    pendingFollow.copy(missingRowTried = false, waitingForSummary = true)
                } else {
                    null
                }
            }
            is Target.Declaration -> {
                if (tree.isModelUpdateInProgress) {
                    pending = pendingFollow
                    return
                }
                val row = findDeclaration(target.fileRow, target.path)
                when {
                    row != null -> {
                        selectPath(tree, treePath(row))
                        pending = null
                    }
                    !pendingFollow.missingRowTried -> pending = pendingFollow.copy(missingRowTried = true, waitingForSummary = true)
                    else -> {
                        selectFile(tree, target.fileRow)
                        pending = null
                    }
                }
            }
            Target.Stale -> pending = pendingFollow.copy(missingRowTried = false, waitingForSummary = true)
            Target.None -> if (pendingFollow.waitingForSummary) pending = pendingFollow else pending = null
        }
    }

    private fun map(editor: Editor, offset: Int, tree: ChangesListView): Target {
        val sideFile = sideFile(editor) ?: return Target.None
        val fileRow = findFileRow(tree, sideFile) ?: return Target.None
        val change = fileRow.userObject as? Change ?: return Target.None
        val settings = SmartDiffSettings.getInstance()
        return when (val snapshot = summaries.snapshot(change)) {
            SummarySnapshot.Stale -> Target.Stale
            SummarySnapshot.None -> Target.FileRow(fileRow, retry = false)
            SummarySnapshot.Pending -> Target.FileRow(fileRow, retry = true)
            is SummarySnapshot.Fresh -> {
                val summary = snapshot.summary
                val summaryRow = fileRow as? SummaryChangeNode
                if (!settings.showChangesSummary && !settings.showChangesDeclarations) {
                    return Target.FileRow(fileRow, retry = false)
                }
                if (summary.items.isEmpty()) {
                    return Target.FileRow(summaryRow ?: fileRow, retry = false)
                }
                if (!settings.showChangesDeclarations) {
                    return Target.FileRow(summaryRow ?: fileRow, retry = false)
                }
                if (summaryRow == null) {
                    return Target.FileRow(fileRow, retry = settings.showChangesSummary || settings.showChangesDeclarations)
                }
                val path = innermost(summary.items, sideFile.side, offset)
                    ?: return Target.FileRow(summaryRow, retry = false)
                val filtered = ChangesTreeFilter.filter(summary.items, settings.hiddenChangesTreeChangeTypes)
                val shown = OutlinePath.of(path).find(filtered.items)
                if (shown.isEmpty()) Target.FileRow(summaryRow, retry = false) else Target.Declaration(summaryRow, OutlinePath.of(shown))
            }
        }
    }

    /** The side and file of [editor]: its own side of a two-sided diff, or the new side of a plain editor. */
    private fun sideFile(editor: Editor): SideFile? {
        if (mergeViewerOf(editor) != null) return null
        val viewer = viewerOf(editor)
        if (viewer != null && viewer !is TwosideTextDiffViewer) return null
        if (viewer is TwosideTextDiffViewer) {
            val side = when (editor) {
                viewer.editor2 -> Side.NEW
                viewer.editor1 -> Side.OLD
                else -> return null
            }
            val own = if (side == Side.NEW) viewer.content2.highlightFile else viewer.content1.highlightFile
            val other = if (side == Side.NEW) viewer.content1.highlightFile else viewer.content2.highlightFile
            val file = own ?: other ?: return null
            return SideFile(side, file, fallback = own == null)
        }
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: editor.virtualFile ?: return null
        return SideFile(Side.NEW, file, fallback = false)
    }

    private fun findFileRow(tree: ChangesListView, sideFile: SideFile): ChangesBrowserChangeNode? {
        val root = tree.model.root as? ChangesBrowserNode<*> ?: return null
        val nodes = root.depthFirstEnumeration()
        while (nodes.hasMoreElements()) {
            val node = nodes.nextElement() as? ChangesBrowserChangeNode ?: continue
            val change = node.userObject as? Change ?: continue
            val onSide = if (sideFile.side == Side.NEW) change.afterRevision?.file else change.beforeRevision?.file
            val other = if (sideFile.side == Side.NEW) change.beforeRevision?.file else change.afterRevision?.file
            // An added or deleted file names only one side; the other content found it, the side still picks the range.
            if (samePath(onSide, sideFile.file) || (sideFile.fallback && samePath(other, sideFile.file))) return node
        }
        return null
    }

    /** The same file by path and kind; a revision path may be a different [FilePath] implementation. */
    private fun samePath(left: FilePath?, right: VirtualFile): Boolean =
        left != null && left.path == right.path && left.isDirectory == right.isDirectory

    /** The rows from the top level to the innermost changed declaration containing [offset], or null. */
    private fun innermost(items: List<OutlineItem>, side: Side, offset: Int): List<OutlineItem>? {
        var best: List<OutlineItem>? = null
        fun visit(item: OutlineItem, path: List<OutlineItem>) {
            val next = path + item
            val range = if (side == Side.NEW) item.newRange else item.oldRange
            if (item.kind == ItemKind.CHANGE && item.tags.isNotEmpty() && range?.contains(offset) == true) {
                if (best == null || next.size > best!!.size) best = next
            }
            item.children.forEach { visit(it, next) }
        }
        items.forEach { visit(it, emptyList()) }
        return best
    }

    private fun findDeclaration(fileRow: SummaryChangeNode, path: OutlinePath): DeclarationNode? =
        fileRow.preorderEnumeration().asSequence().filterIsInstance<DeclarationNode>().firstOrNull { it.path.matches(path) }

    private fun selectFile(tree: ChangesListView, row: ChangesBrowserChangeNode) =
        selectPath(tree, treePath(row))

    private fun treePath(node: DefaultMutableTreeNode): TreePath = TreePath(node.path)

    private fun selectPath(tree: ChangesListView, path: TreePath) {
        following++
        try {
            // Make every ancestor visible first; TreeUtil.selectPath only scrolls to a path that has bounds.
            var parent = path.parentPath
            while (parent != null) {
                tree.expandPath(parent)
                parent = parent.parentPath
            }
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } finally {
            following--
        }
    }

    private fun cancelPending() {
        timer?.stop()
        pending = null
        selectionEpoch.incrementAndGet()
    }

    private fun modelChanged() {
        if (pending != null) restartTimer()
    }

    private fun syncTree(): ChangesListView? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!started || project.isDisposed) return null
        val tree = treeProvider()
        if (tree !== watchedTree) {
            detachTree()
            if (tree != null) attachTree(tree)
        }
        return tree
    }

    private fun attachTree(tree: ChangesListView) {
        watchedTree = tree
        watchedModel = tree.model
        watchedModel?.addTreeModelListener(modelListener)
        tree.addPropertyChangeListener("model", modelPropertyListener)
        tree.addTreeSelectionListener(selectionListener)
        tree.addMouseListener(mouseListener)
        tree.addKeyListener(keyListener)
    }

    private fun detachTree() {
        val tree = watchedTree ?: return
        watchedModel?.removeTreeModelListener(modelListener)
        tree.removePropertyChangeListener("model", modelPropertyListener)
        tree.removeTreeSelectionListener(selectionListener)
        tree.removeMouseListener(mouseListener)
        tree.removeKeyListener(keyListener)
        watchedTree = null
        watchedModel = null
    }

    private fun isTreeFocused(): Boolean {
        val tree = watchedTree ?: return false
        val focus = focusOwner() ?: return false
        return focus === tree || SwingUtilities.isDescendingFrom(focus, tree)
    }

    private fun editorContaining(component: Component): Editor? =
        EditorFactory.getInstance().allEditors.firstOrNull {
            component === it.contentComponent || SwingUtilities.isDescendingFrom(component, it.contentComponent)
        }

    private fun debounceDelay(): Int = try {
        Registry.get(AUTOSCROLL_DELAY_KEY).asInteger().takeIf { it > 0 } ?: DEFAULT_DEBOUNCE_MILLIS
    } catch (e: Exception) {
        DEFAULT_DEBOUNCE_MILLIS
    }

    companion object {
        /** The platform's autoscroll-from-source delay, which gives following the same feel and coalesces typing. */
        internal const val AUTOSCROLL_DELAY_KEY = "ide.autoscroll.from.source.delay"

        internal const val DEFAULT_DEBOUNCE_MILLIS = 100

        fun getInstance(project: Project): ChangesTreeCaretFollower = project.service()

        internal fun liveTree(project: Project): ChangesListView? {
            val manager = ToolWindowManager.getInstance(project)
            for (id in listOf(ToolWindowId.VCS, ToolWindowId.COMMIT)) {
                val toolWindow = manager.getToolWindow(id) ?: continue
                for (content in toolWindow.contentManager.contents) {
                    findChangesTree(content.component)?.let { return it }
                }
            }
            return null
        }

        private fun findChangesTree(component: Component?): ChangesListView? {
            if (component is ChangesListView) return component
            if (component is Container) {
                for (child in component.components) findChangesTree(child)?.let { return it }
            }
            return null
        }
    }
}

/** Creates the caret follower on the EDT when a project opens, before the first caret move. */
class ChangesTreeCaretFollowActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) ChangesTreeCaretFollower.getInstance(project).start()
        }
    }
}

/** The diff viewer of [editor] from its data context, or null for a plain editor. */
internal fun viewerFromContext(editor: Editor): FrameDiffTool.DiffViewer? =
    DiffDataKeys.DIFF_VIEWER.getData(DataManager.getInstance().getDataContext(editor.contentComponent))

/** The merge viewer of [editor], which marks a merge window that following leaves alone. */
internal fun mergeViewerFromContext(editor: Editor): MergeTool.MergeViewer? =
    DiffDataKeys.MERGE_VIEWER.getData(DataManager.getInstance().getDataContext(editor.contentComponent))

private fun logFollowFailure(error: Throwable) {
    Logger.getInstance(ChangesTreeCaretFollower::class.java).warn("Cannot follow the caret in the Changes tree", error)
}
