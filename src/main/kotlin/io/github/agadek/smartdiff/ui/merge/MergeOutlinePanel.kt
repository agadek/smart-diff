package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.util.TextDiffType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.registry.EngineSelection
import io.github.agadek.smartdiff.ui.ChangeType
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import io.github.agadek.smartdiff.ui.Tag
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The merge outline panel for a three-sided diff (merge outline spec 02): summary, engine dropdown, layout toggle,
 * change type filter, refresh, the merge rows and the three-pane selection.
 */
class MergeOutlinePanel internal constructor(private val controller: MergeOutlineController) : JPanel(BorderLayout()) {

    internal val summary = JBLabel()
    internal val banner = JBLabel("", AllIcons.General.Warning, SwingConstants.LEADING)
    internal val engines = ComboBox<ComparisonEngine>()
    internal val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode()))
    internal val message = JBLabel("", SwingConstants.CENTER)
    internal val absence = JBLabel()

    private val body = JPanel(CardLayout())
    private var updatingEngines = false

    init {
        name = "smartdiff.merge.outline"

        val refresh = object : DumbAwareAction("Refresh", "Compare the three versions again", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = controller.refresh()
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }
        val layout = LayoutAction()
        val filter = DefaultActionGroup("Hide Change Types", ChangeType.entries.map(::HideAction) + HideBothSidesAction()).apply {
            templatePresentation.description = "Hide types of changes from the merge outline"
            templatePresentation.icon = AllIcons.General.Filter
            isPopup = true
        }
        val toolbarActions = DefaultActionGroup(layout, filter).apply {
            if (controller.showsRefresh) add(refresh)
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("SmartDiffMergeOutline", toolbarActions, true)
        toolbar.targetComponent = this

        engines.renderer = object : SimpleListCellRenderer<ComparisonEngine>() {
            override fun customize(list: JList<out ComparisonEngine>, value: ComparisonEngine?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.displayName ?: ""
            }
        }
        engines.accessibleContext.accessibleName = "Comparison engine"
        engines.isVisible = false
        engines.addActionListener {
            val engine = engines.selectedItem as? ComparisonEngine
            if (!updatingEngines && engine != null) controller.chooseEngine(engine.id)
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(engines)
            add(toolbar.component)
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 6, 2, 0)
            add(summary, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
            add(banner, BorderLayout.SOUTH)
        }
        banner.isVisible = false
        banner.border = JBUI.Borders.emptyTop(2)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = Renderer()
        tree.accessibleContext.accessibleName = "Merge outline"
        tree.addTreeSelectionListener { select(selectedItem()) }
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) selectedItem()?.let { controller.selection.navigate(it); e.consume() }
            }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val item = selectedItem() ?: return false
                controller.selection.navigate(item)
                return true
            }
        }.installOn(tree)

        message.foreground = UIUtil.getContextHelpForeground()
        message.border = JBUI.Borders.empty(8)
        absence.foreground = UIUtil.getContextHelpForeground()
        absence.border = JBUI.Borders.empty(2, 6)

        body.add(ScrollPaneFactory.createScrollPane(tree, true), TREE)
        body.add(message, MESSAGE)
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        add(absence, BorderLayout.SOUTH)
    }

    internal fun showEngines(selection: EngineSelection) {
        updatingEngines = true
        try {
            engines.removeAllItems()
            selection.candidates.forEach(engines::addItem)
            engines.selectedItem = selection.selected
            engines.isVisible = controller.showsEngineChoice && selection.offersChoice
        } finally {
            updatingEngines = false
        }
    }

    internal fun show(state: MergeOutlineState) {
        controller.selection.clear()
        absence.text = ""
        banner.isVisible = false
        when (state) {
            MergeOutlineState.Hidden -> {
                summary.text = ""
                setTree(emptyList())
            }
            MergeOutlineState.Loading -> showMessage("", "Comparing…")
            is MergeOutlineState.NoEngine -> showMessage("", "No comparison engine supports ${state.languageName} files. The native diff shows every change.")
            is MergeOutlineState.Failed -> showMessage("", "${state.reason}. The native diff shows every change.")
            is MergeOutlineState.Shown -> {
                summary.text = state.summary
                banner.text = state.banner.orEmpty()
                banner.isVisible = state.banner != null
                banner.accessibleContext.accessibleName = state.banner.orEmpty()
                setTree(state.items)
                tree.emptyText.text = if (state.hiddenCount > 0) "All changes are hidden by the filter" else "No structural changes"
                (body.layout as CardLayout).show(body, TREE)
            }
        }
        revalidate()
        repaint()
    }

    private fun showMessage(summaryText: String, text: String) {
        summary.text = summaryText
        setTree(emptyList())
        message.text = "<html><div style='text-align:center'>${escape(text)}</div></html>"
        message.accessibleContext.accessibleName = text
        (body.layout as CardLayout).show(body, MESSAGE)
    }

    private fun setTree(items: List<MergeOutlineItem>) {
        val root = DefaultMutableTreeNode()
        fun add(parent: DefaultMutableTreeNode, item: MergeOutlineItem) {
            val node = DefaultMutableTreeNode(item)
            parent.add(node)
            item.children.forEach { add(node, it) }
        }
        items.forEach { add(root, it) }
        tree.model = DefaultTreeModel(root)
        expand(root)
    }

    /** Expands every row and group; merge rows are few and the tree is the only outline for the diff. */
    private fun expand(node: DefaultMutableTreeNode) {
        for (child in node.children()) {
            child as DefaultMutableTreeNode
            if (child.isLeaf) continue
            tree.expandPath(TreePath(child.path))
            expand(child)
        }
    }

    internal fun selectedItem(): MergeOutlineItem? =
        (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? MergeOutlineItem

    private fun select(item: MergeOutlineItem?) {
        controller.selection.select(item)
        absence.text = controller.noteFor(item)
    }

    private inner class LayoutAction : DumbAwareToggleAction("Group by side") {
        override fun isSelected(e: AnActionEvent): Boolean =
            (controller.state as? MergeOutlineState.Shown)?.layout == MergeOutlineLayout.GROUPED_BY_SIDE ||
                (controller.state !is MergeOutlineState.Shown && SmartDiffSettings.getInstance().mergeGroupedBySide)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            controller.setLayout(if (state) MergeOutlineLayout.GROUPED_BY_SIDE else MergeOutlineLayout.CONTAINER_TREE)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** One entry of the filter menu: checked while changes of [type] are hidden. */
    private inner class HideAction(private val type: ChangeType) : DumbAwareToggleAction(type.text) {
        override fun isSelected(e: AnActionEvent): Boolean = SmartDiffSettings.getInstance().hiddenChangeTypes.contains(type)

        override fun setSelected(e: AnActionEvent, state: Boolean) = controller.setHidden(type, state)

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** The merge-only filter entry for rows changed on both sides (merge outline spec 02 R15). */
    private inner class HideBothSidesAction : DumbAwareToggleAction("Changed on both sides") {
        override fun update(e: AnActionEvent) {
            super.update(e)
            val layout = (controller.state as? MergeOutlineState.Shown)?.layout
                ?: if (SmartDiffSettings.getInstance().mergeGroupedBySide) MergeOutlineLayout.GROUPED_BY_SIDE else MergeOutlineLayout.CONTAINER_TREE
            e.presentation.isEnabledAndVisible = layout == MergeOutlineLayout.CONTAINER_TREE
        }

        override fun isSelected(e: AnActionEvent): Boolean = SmartDiffSettings.getInstance().mergeHideBothSides

        override fun setSelected(e: AnActionEvent, state: Boolean) = controller.setHideBothSides(state)

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val item = (value as? DefaultMutableTreeNode)?.userObject as? MergeOutlineItem ?: return
            appendMergeItem(this, item)
            toolTipText = item.tooltip
            accessibleContext?.let { it.accessibleName = item.accessibleName }
        }
    }

    private companion object {
        const val TREE = "tree"
        const val MESSAGE = "message"

        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

/** Draws a merge row: its title, then each side's name and labels, with the same text colors as the two-sided outline. */
internal fun appendMergeItem(component: SimpleColoredComponent, item: MergeOutlineItem) {
    component.append(item.title, if (item.isGroup) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
    item.left?.let { appendSide(component, it) }
    item.right?.let { appendSide(component, it) }
    item.note?.let {
        component.append("  ")
        component.append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}

private fun appendSide(component: SimpleColoredComponent, label: MergeSideLabel) {
    component.append("  ")
    component.append("${label.name}: ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    component.append(label.value, attributes(label.tags))
}

private fun attributes(tags: List<Tag>): SimpleTextAttributes = when {
    tags.any { it == Tag.ADDED } -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, TextDiffType.INSERTED.getMarkerColor(null))
    tags.any { it == Tag.REMOVED } -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, TextDiffType.DELETED.getMarkerColor(null))
    tags.any { it == Tag.CHANGED || it == Tag.SIGNATURE_CHANGED } ->
        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, TextDiffType.MODIFIED.getMarkerColor(null))
    else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
}
