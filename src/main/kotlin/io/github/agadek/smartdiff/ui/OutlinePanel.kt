package io.github.agadek.smartdiff.ui

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
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.registry.EngineSelection
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
 * The structural outline beside the diff editors (technical specification §10): a file summary, the engine dropdown
 * and refresh action, a banner for partial results, and the tree of changes or a status message in its place.
 */
class OutlinePanel internal constructor(private val controller: OutlineController) : JPanel(BorderLayout()) {

    internal val summary = JBLabel()
    internal val banner = JBLabel("Some source was not analyzed; see the Unanalyzed group.", AllIcons.General.Warning, SwingConstants.LEADING)
    internal val engines = ComboBox<ComparisonEngine>()
    internal val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode()))
    internal val message = JBLabel("", SwingConstants.CENTER)

    /** Says which side the selected item is absent from (§10). */
    internal val absence = JBLabel()

    private val body = JPanel(CardLayout())
    private var updatingEngines = false

    init {
        name = "smartdiff.outline"

        val refresh = object : DumbAwareAction("Refresh", "Compare the two sides again", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = controller.refresh()
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }
        val filter = DefaultActionGroup("Hide Change Types", ChangeType.entries.map(::HideAction)).apply {
            templatePresentation.description = "Hide types of changes from the outline"
            templatePresentation.icon = AllIcons.General.Filter
            isPopup = true
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("SmartDiffOutline", DefaultActionGroup(filter, refresh), true)
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
        tree.accessibleContext.accessibleName = "Structural outline"
        tree.addTreeSelectionListener { select(selectedItem()) }
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) selectedItem()?.let { controller.navigator.navigate(it); e.consume() }
            }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val item = selectedItem() ?: return false
                controller.navigator.navigate(item)
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

    /** Lists the candidate engines in the dropdown, which is shown only when there is a choice (§4). */
    internal fun showEngines(selection: EngineSelection) {
        updatingEngines = true
        try {
            engines.removeAllItems()
            selection.candidates.forEach(engines::addItem)
            engines.selectedItem = selection.selected
            engines.isVisible = selection.offersChoice
        } finally {
            updatingEngines = false
        }
    }

    internal fun show(state: OutlineState) {
        controller.navigator.clear()
        absence.text = ""
        banner.isVisible = false
        when (state) {
            OutlineState.Hidden -> {
                summary.text = ""
                setTree(emptyList())
            }
            OutlineState.Loading -> showMessage("", "Comparing…")
            is OutlineState.NoEngine -> showMessage("", "No comparison engine supports ${state.languageName} files. The native diff shows every change.")
            is OutlineState.Shown -> when (state.result.status) {
                Status.UNSUPPORTED -> showFallback("Not supported", state)
                Status.TOO_LARGE -> showFallback("Too large to analyze", state)
                Status.FAILED -> showFallback("Analysis failed", state)
                Status.ANALYZED, Status.PARTIAL -> {
                    summary.text = state.summary
                    banner.isVisible = state.result.status == Status.PARTIAL
                    setTree(state.items)
                    tree.emptyText.text = if (state.hiddenCount > 0) "All changes are hidden by the filter" else "No structural changes"
                    (body.layout as CardLayout).show(body, TREE)
                }
            }
        }
        revalidate()
        repaint()
    }

    private fun showFallback(title: String, state: OutlineState.Shown) {
        val reason = state.result.fallbackReason?.let { ": $it" } ?: ""
        showMessage("", "$title$reason. The native diff shows every change.")
    }

    private fun showMessage(summaryText: String, text: String) {
        summary.text = summaryText
        setTree(emptyList())
        message.text = "<html><div style='text-align:center'>${escape(text)}</div></html>"
        message.accessibleContext.accessibleName = text
        (body.layout as CardLayout).show(body, MESSAGE)
    }

    private fun setTree(items: List<OutlineItem>) {
        val root = DefaultMutableTreeNode()
        fun add(parent: DefaultMutableTreeNode, item: OutlineItem) {
            val node = DefaultMutableTreeNode(item)
            parent.add(node)
            item.children.forEach { add(node, it) }
        }
        items.forEach { add(root, it) }
        tree.model = DefaultTreeModel(root)
        expand(root)
    }

    /** Expands everything except the formatting-only groups, which start collapsed (§10). */
    private fun expand(node: DefaultMutableTreeNode) {
        for (child in node.children()) {
            child as DefaultMutableTreeNode
            if ((child.userObject as OutlineItem).kind == ItemKind.FORMATTING_GROUP || child.isLeaf) continue
            tree.expandPath(TreePath(child.path))
            expand(child)
        }
    }

    /** Selects the row of [path], or the nearest row above it that is shown, and scrolls it into view. */
    internal fun reveal(path: OutlinePath) {
        val root = tree.model.root as DefaultMutableTreeNode
        val items = root.children().toList().map { (it as DefaultMutableTreeNode).userObject as OutlineItem }
        var node = root
        for (item in path.find(items)) {
            node = node.children().toList().map { it as DefaultMutableTreeNode }.firstOrNull { it.userObject === item } ?: break
        }
        if (node === root) return
        val treePath = TreePath(node.path)
        tree.selectionPath = treePath
        tree.scrollPathToVisible(treePath)
    }

    internal fun selectedItem(): OutlineItem? =
        (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? OutlineItem

    private fun select(item: OutlineItem?) {
        controller.navigator.select(item)
        absence.text = when {
            item == null || item.isGroup -> ""
            item.oldRange == null && item.newRange == null -> ""
            item.oldRange == null -> "Not present on the old side"
            item.newRange == null -> "Not present on the new side"
            else -> ""
        }
    }

    /** One entry of the filter menu: checked while changes of [type] are hidden. */
    private inner class HideAction(private val type: ChangeType) : DumbAwareToggleAction(type.text) {
        override fun isSelected(e: AnActionEvent): Boolean = type in SmartDiffSettings.getInstance().hiddenChangeTypes
        override fun setSelected(e: AnActionEvent, state: Boolean) = controller.setHidden(type, state)
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Draws an item as [appendItem] does. */
    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val item = (value as? DefaultMutableTreeNode)?.userObject as? OutlineItem ?: return
            appendItem(this, item)
        }
    }

    private companion object {
        const val TREE = "tree"
        const val MESSAGE = "message"

        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

/**
 * Draws an outline item's title, then its change labels in the IDE's diff colors and its note in grey; the text alone
 * carries the meaning (§10). The outline and the Changes tree (§9.4) draw their rows with it.
 */
internal fun appendItem(component: SimpleColoredComponent, item: OutlineItem) {
    component.append(item.title, if (item.isGroup) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
    if (item.tags.isEmpty() && item.note == null) return
    component.append("  ")
    item.tags.forEachIndexed { i, tag ->
        if (i > 0) component.append(", ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        component.append(tag.text, attributes(tag))
    }
    item.note?.let { component.append((if (item.tags.isEmpty()) "" else ", ") + it, SimpleTextAttributes.GRAYED_ATTRIBUTES) }
}

private fun attributes(tag: Tag): SimpleTextAttributes {
    val type = when (tag) {
        Tag.ADDED -> TextDiffType.INSERTED
        Tag.REMOVED -> TextDiffType.DELETED
        Tag.CHANGED, Tag.SIGNATURE_CHANGED -> TextDiffType.MODIFIED
        else -> return SimpleTextAttributes.GRAYED_ATTRIBUTES
    }
    return SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, type.getMarkerColor(null))
}
