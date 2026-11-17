package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import io.github.agadek.smartdiff.ui.OutlinePath

/**
 * The Git log's commit details tree (changes-tree/07 R1, ADR 0013), registered by `smartdiff-vcs-log.xml`.
 *
 * The tree is the viewer of the log's changes browser, recognised by the browser's class name so the plugin does not
 * link against the log's internal module. Each file node holds the `Change` the log's own diff opens: the parent
 * against the commit, the change of a range, or one change per parent of a merge (R4). A declaration row opens that
 * change through the same platform diff action as the Changes tree, with the tree's other changes next to it (R10).
 */
class GitLogTreeAdapter @JvmOverloads constructor(
    private val openDiff: (Project, List<Change>, Int, Key<*>, Any) -> Boolean = ChangesDiff::show,
) : ReviewTreeAdapter {

    override val name: String = "Git log"

    override fun accepts(tree: ChangesTree): Boolean =
        tree !is ChangesListView && ReviewTreeWatcher.hasAncestorOfClass(tree, BROWSER_CLASS)

    override fun changeOf(tree: ChangesTree, node: ChangesBrowserNode<*>): Change? = node.userObject as? Change

    override fun open(tree: ChangesTree, node: ReviewSummaryNode, path: OutlinePath): Boolean {
        val changes = VcsTreeModelData.all(tree).userObjects(Change::class.java).distinct()
        val index = changes.indexOf(node.change)
        if (index < 0) return openDiff(tree.project, listOf(node.change), 0, OutlinePath.KEY, path)
        return openDiff(tree.project, changes, index, OutlinePath.KEY, path)
    }

    companion object {
        const val BROWSER_CLASS = "com.intellij.vcs.log.ui.frame.VcsLogChangesBrowser"
    }
}
