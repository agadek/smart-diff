package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Processor
import com.intellij.util.ui.tree.ExpandOnDoubleClick
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseEvent

/**
 * Makes a summarized file row behave like the platform's file rows.
 *
 * A row with children is not a leaf, so the Changes tree would expand it on a double-click and skip Enter for it.
 * While the tree has declaration rows, double-click expansion is turned off for the tree and the handlers open a
 * [SummaryChangeNode] through the platform and a [DeclarationNode] through [DeclarationNodeAction]. Other rows with
 * children keep their double-click expansion through [ChangesDoubleClickHandler]. The declaration action cannot be
 * reached through `ChangesViewNodeAction` alone: the platform looks the selected node up with
 * `VcsTreeModelData.selected(...).iterateNodes()`, which filters out helper nodes, so the handlers use the selected
 * path instead.
 */
internal object ChangesTreeSetup {

    /** The client property `ExpandOnDoubleClick` uses; there is no uninstall method to remove it again. */
    private const val EXPAND_ON_DOUBLE_CLICK_KEY = "ide.tree.expand.on.double.click"

    /** Our marker for the client property, so the handler knows whether ordinary rows need a manual toggle. */
    private val EXPANSION_SUPPRESSED = Key.create<Boolean>("smartdiff.changes.tree.expansionSuppressed")

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
            ExpandOnDoubleClick.NEVER.installOn(tree)
            tree.putClientProperty(EXPANSION_SUPPRESSED, true)
            installEnterHandler(tree)
            installDoubleClickHandler(tree)
        } else {
            tree.putClientProperty(EXPAND_ON_DOUBLE_CLICK_KEY, null)
            tree.putClientProperty(EXPANSION_SUPPRESSED, null)
        }
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
        val ours = tree.keyListeners.filterIsInstance<ChangesEnterListener>().singleOrNull()
        val platform = tree.keyListeners.firstOrNull(::isPlatformEnterListener)
        if (ours != null && platform == null && tree.keyListeners.firstOrNull() === ours) return

        val others = tree.keyListeners.filterNot { it is ChangesEnterListener || isPlatformEnterListener(it) }
        others.forEach(tree::removeKeyListener)
        ours?.let(tree::removeKeyListener)
        platform?.let(tree::removeKeyListener)
        val listener = if (platform != null || ours == null) ChangesEnterListener(tree, wrapped = platform != null) else ours
        tree.addKeyListener(listener)
        others.forEach(tree::addKeyListener)
    }

    private fun isPlatformEnterListener(listener: KeyListener): Boolean =
        listener.javaClass.name.endsWith("ChangesTreeHandlers\$MyEnterListener")

    private fun openDeclaration(tree: ChangesListView, node: DeclarationNode): Boolean =
        DeclarationNodeAction(tree.project).handleDoubleClick(node)

    private class ChangesDoubleClickHandler(
        private val tree: ChangesListView,
        private val platform: Processor<in MouseEvent>?,
    ) : Processor<MouseEvent> {

        override fun process(e: MouseEvent): Boolean {
            val path = TreeUtil.getPathForLocation(tree, e.x, e.y) ?: return platform?.process(e) ?: false
            if (TreeUtil.isLocationInExpandControl(tree, path, e.x, e.y)) return false

            val node = path.lastPathComponent
            if (node is DeclarationNode) return openDeclaration(tree, node)
            if (node is SummaryChangeNode) return platform?.process(e) ?: false
            if (tree.getClientProperty(EXPANSION_SUPPRESSED) == true &&
                node is ChangesBrowserNode<*> &&
                !tree.model.isLeaf(node) &&
                !isStructural(node)
            ) {
                if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                return true
            }
            return platform?.process(e) ?: false
        }

        private fun isStructural(node: Any): Boolean =
            node.javaClass.name.contains("ChangesBrowserStructuralNode")
    }

    private class ChangesEnterListener(
        private val tree: ChangesListView,
        private val wrapped: Boolean,
    ) : KeyAdapter() {

        override fun keyPressed(e: KeyEvent) {
            if (e.keyCode != KeyEvent.VK_ENTER || e.modifiersEx != 0 || e.isConsumed) return

            if (tree.selectionCount == 1) {
                when (val node = tree.selectionPath?.lastPathComponent) {
                    is DeclarationNode -> {
                        if (openDeclaration(tree, node)) e.consume()
                        return
                    }
                    is SummaryChangeNode -> if (!tree.model.isLeaf(node)) {
                        if (tree.enterKeyHandler?.process(e) == true) e.consume()
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
