package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Processor
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath

/**
 * Makes a summarized file row behave like the platform's file rows.
 *
 * A row with children is not a leaf, so the Changes tree would expand it on a double-click and skip Enter for it.
 * While the tree has declaration rows, the press listener stops the second press of a double-click on a
 * [SummaryChangeNode] or [DeclarationNode] before the platform's tree UI sees it, and the tree's handlers open the
 * summary through the platform and the declaration through [DeclarationNodeAction]. Other rows keep their normal
 * double-click expansion. The declaration action cannot be reached through `ChangesViewNodeAction` alone: the platform
 * looks the selected node up with `VcsTreeModelData.selected(...).iterateNodes()`, which filters out helper nodes, so
 * the handlers use the selected path instead.
 */
internal object ChangesTreeSetup {

    /** Whether [ChangesPressListener] must suppress the second press of a SMART row's double-click. */
    private val EXPANSION_SUPPRESSED = Key.create<Boolean>("smartdiff.changes.tree.expansionSuppressed")

    /** The tree's toggle click count before the setup changed it. */
    private val SAVED_TOGGLE_CLICK_COUNT = Key.create<Int>("smartdiff.changes.tree.savedToggleClickCount")

    /** Applies the setup to the project's Changes tree after the tree has been built, on the EDT. */
    fun schedule(project: Project, suppressExpansion: Boolean) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) installIn(project, suppressExpansion)
        }, ModalityState.nonModal(), project.disposed)
    }

    /** Finds the Local Changes or Commit tree and applies the setup to it. */
    fun installIn(project: Project, suppressExpansion: Boolean) {
        val manager = ToolWindowManager.getInstance(project)
        for (id in listOf(ToolWindowId.VCS, ToolWindowId.COMMIT)) {
            val toolWindow = manager.getToolWindow(id) ?: continue
            for (content in toolWindow.contentManager.contents) {
                findChangesTree(content.component)?.let {
                    configure(it, suppressExpansion)
                    return
                }
            }
        }
    }

    /** Applies or removes the setup on [tree]; safe to call more than once. */
    fun configure(tree: ChangesListView, suppressExpansion: Boolean) {
        if (suppressExpansion) {
            if (tree.getClientProperty(SAVED_TOGGLE_CLICK_COUNT) == null) {
                tree.putClientProperty(SAVED_TOGGLE_CLICK_COUNT, tree.toggleClickCount)
            }
            // ChangesListView overrides getToggleClickCount() to 2, so this alone is not enough; the press listener
            // below stops the second press before BasicTreeUI's toggle decision.
            tree.setToggleClickCount(0)
            tree.putClientProperty(EXPANSION_SUPPRESSED, true)
            installPressHandler(tree)
            installEnterHandler(tree)
            installDoubleClickHandler(tree)
        } else {
            (tree.getClientProperty(SAVED_TOGGLE_CLICK_COUNT) as? Int)?.let(tree::setToggleClickCount)
            tree.putClientProperty(SAVED_TOGGLE_CLICK_COUNT, null)
            tree.putClientProperty(EXPANSION_SUPPRESSED, null)
        }
    }

    private fun installPressHandler(tree: ChangesListView) {
        val ours = tree.mouseListeners.filterIsInstance<ChangesPressListener>().singleOrNull()
        if (ours != null && tree.mouseListeners.firstOrNull() === ours) return

        val others = tree.mouseListeners.filterNot { it is ChangesPressListener }
        others.forEach(tree::removeMouseListener)
        ours?.let(tree::removeMouseListener)
        tree.addMouseListener(ours ?: ChangesPressListener(tree))
        others.forEach(tree::addMouseListener)
    }

    private fun installDoubleClickHandler(tree: ChangesListView) {
        if (tree.doubleClickHandler is ChangesDoubleClickHandler) return
        tree.doubleClickHandler = ChangesDoubleClickHandler(tree, tree.doubleClickHandler)
    }

    /**
     * Replaces the platform's Enter listener with one that keeps its leaf-only guard for ordinary rows and bypasses it
     * for a summarized file or a declaration. The platform's listener is private, so it is recognised by its class.
     */
    private fun installEnterHandler(tree: ChangesListView) {
        val listeners = tree.keyListeners
        val ours = listeners.filterIsInstance<ChangesEnterListener>().singleOrNull()
        val platform = listeners.firstOrNull(::isPlatformEnterListener)

        if (ours != null && (ours.wrapped || platform == null)) {
            platform?.let(tree::removeKeyListener)
            if (tree.keyListeners.firstOrNull() !== ours) moveToFront(tree, ours)
            return
        }

        val others = listeners.filterNot { it is ChangesEnterListener || isPlatformEnterListener(it) }
        others.forEach(tree::removeKeyListener)
        ours?.let(tree::removeKeyListener)
        platform?.let(tree::removeKeyListener)
        tree.addKeyListener(ChangesEnterListener(tree, wrapped = ours?.wrapped == true || platform != null))
        others.forEach(tree::addKeyListener)
    }

    private fun moveToFront(tree: ChangesListView, listener: KeyListener) {
        val others = tree.keyListeners.filterNot { it === listener }
        others.forEach(tree::removeKeyListener)
        tree.removeKeyListener(listener)
        tree.addKeyListener(listener)
        others.forEach(tree::addKeyListener)
    }

    private fun isPlatformEnterListener(listener: KeyListener): Boolean =
        listener.javaClass.name.endsWith("ChangesTreeHandlers\$MyEnterListener")

    private fun openDeclaration(tree: ChangesListView, node: DeclarationNode): Boolean =
        DeclarationNodeAction(tree.project).handleDoubleClick(node)

    private fun openFile(tree: ChangesListView, node: SummaryChangeNode): Boolean {
        val change = node.userObject as? Change ?: return false
        val root = node.root as? ChangesBrowserNode<*> ?: return false
        val changes = VcsTreeModelData.allUnder(root).userObjects(Change::class.java).distinct()
        return ChangesDiff.show(tree.project, changes, changes.indexOf(change).coerceAtLeast(0))
    }

    /**
     * The second press of a double-click on a SMART row is consumed before `BasicTreeUI` can toggle the row. The first
     * press still selects the row and the release still reaches the platform's double-click handler.
     */
    private class ChangesPressListener(private val tree: ChangesListView) : MouseAdapter() {

        override fun mousePressed(e: MouseEvent) {
            if (tree.getClientProperty(EXPANSION_SUPPRESSED) != true) return
            if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount < 2) return

            val path = tree.getPathForLocation(e.x, e.y) ?: return
            val node = path.lastPathComponent
            if (node !is SummaryChangeNode && node !is DeclarationNode) return
            if (isExpandControlClick(tree, path, e)) return

            tree.selectionPath = path
            e.consume()
        }
    }

    /** A public hit test for the tree's expand control, left of the row bounds in a left-to-right tree. */
    private fun isExpandControlClick(tree: ChangesListView, path: TreePath, e: MouseEvent): Boolean {
        val bounds = tree.getPathBounds(path) ?: return false
        if (e.y < bounds.y || e.y >= bounds.y + bounds.height) return false
        return if (tree.componentOrientation.isLeftToRight) e.x < bounds.x else e.x >= bounds.x + bounds.width
    }

    private class ChangesDoubleClickHandler(
        private val tree: ChangesListView,
        private val platform: Processor<in MouseEvent>?,
    ) : Processor<MouseEvent> {

        override fun process(e: MouseEvent): Boolean {
            val path = tree.getPathForLocation(e.x, e.y) ?: return platform?.process(e) ?: false
            if (isExpandControlClick(tree, path, e)) return false

            val node = path.lastPathComponent
            if (node is DeclarationNode) return openDeclaration(tree, node)
            if (node is SummaryChangeNode) return openFile(tree, node) || (platform?.process(e) ?: false)
            return platform?.process(e) ?: false
        }
    }

    private class ChangesEnterListener(
        private val tree: ChangesListView,
        val wrapped: Boolean,
    ) : KeyAdapter() {

        override fun keyPressed(e: KeyEvent) {
            if (e.keyCode != KeyEvent.VK_ENTER || e.modifiersEx != 0 || e.isConsumed) return

            if (tree.selectionCount == 1) {
                when (val node = tree.selectionPath?.lastPathComponent) {
                    is DeclarationNode -> {
                        // Without the platform listener, a non-leaf declaration would be skipped by its leaf-only
                        // guard; a leaf one must still be left to that listener to avoid opening it twice.
                        if ((wrapped || !tree.model.isLeaf(node)) && openDeclaration(tree, node)) e.consume()
                        return
                    }
                    is SummaryChangeNode -> if (!tree.model.isLeaf(node)) {
                        if (openFile(tree, node) || tree.enterKeyHandler?.process(e) == true) e.consume()
                        return
                    }
                }
            }

            if (!wrapped) return
            val handler = tree.enterKeyHandler ?: return
            if (tree.selectionCount <= 1 && tree.lastSelectedPathComponent?.let { tree.model.isLeaf(it) } != true) return
            if (handler.process(e)) e.consume()
        }
    }

    private fun findChangesTree(component: Component?): ChangesListView? {
        if (component is ChangesListView) return component
        if (component is Container) {
            for (child in component.components) findChangesTree(child)?.let { return it }
        }
        return null
    }
}
