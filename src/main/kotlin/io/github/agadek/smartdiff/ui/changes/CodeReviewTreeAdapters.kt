package io.github.agadek.smartdiff.ui.changes

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import git4idea.GitContentRevision
import io.github.agadek.smartdiff.ui.OutlinePath

/**
 * A pull or merge request changes tree, built by the platform's collaboration tools for GitHub and GitLab alike
 * (changes-tree/07 R2, R3, ADR 0013). Its file nodes hold a [RefComparisonChange]: the base and head revisions and paths
 * the request's diff compares (R4). They become a [Change] whose revisions are read only when the summary executor asks
 * for their content (R16); a revision whose commit is not fetched yet cannot be read, so the row stays plain (R9).
 *
 * A declaration row opens the file's diff through the tree's own handler, which goes through the request's view model,
 * with the outline path waiting for that diff in [PendingOutlineReveal] (R10, R11).
 */
abstract class CodeReviewTreeAdapter(
    final override val name: String,
    private val toolWindowId: String,
    private val pluginPackage: String,
) : ReviewTreeAdapter {

    override fun accepts(tree: ChangesTree): Boolean {
        if (!hasReviewChanges(tree)) return false
        return ReviewTreeWatcher.isInToolWindow(tree.project, tree, toolWindowId) ||
            ReviewTreeWatcher.hasAncestorInPackage(tree, pluginPackage)
    }

    /** Whether [tree] shows request changes; an empty tree is recognised by its class, which it gets from the factory. */
    private fun hasReviewChanges(tree: ChangesTree): Boolean =
        tree.javaClass.name.startsWith(FACTORY_CLASS) ||
            tree.root.depthFirstEnumeration().asSequence().any { (it as? ChangesBrowserNode<*>)?.userObject is RefComparisonChange }

    override fun changeOf(tree: ChangesTree, node: ChangesBrowserNode<*>): Change? {
        val change = node.userObject as? RefComparisonChange ?: return null
        return toChange(tree.project, change)
    }

    override fun open(tree: ChangesTree, node: ReviewSummaryNode, path: OutlinePath): Boolean = openThroughTree(tree, node, path)

    companion object {
        private const val FACTORY_CLASS = "com.intellij.collaboration.ui.codereview.changes.CodeReviewChangeListComponentFactory"

        /** [change] as a [Change] with lazily read Git revisions; an absent side makes an added or deleted file. */
        fun toChange(project: Project, change: RefComparisonChange): Change {
            val before = change.filePathBefore?.let { LazyGitRevision(project, it, change.revisionNumberBefore) }
            val after = change.filePathAfter?.let { LazyGitRevision(project, it, change.revisionNumberAfter) }
            return Change(before, after)
        }
    }
}

/** A Git revision of [path] whose content is read through Git only when asked for, off the EDT. */
internal class LazyGitRevision(
    private val project: Project,
    private val path: FilePath,
    private val number: VcsRevisionNumber,
) : ContentRevision {
    private val delegate: ContentRevision by lazy { GitContentRevision.createRevision(path, number, project) }

    override fun getContent(): String? = delegate.content

    override fun getFile(): FilePath = path

    override fun getRevisionNumber(): VcsRevisionNumber = number
}

/** The changes of a GitHub pull request, registered by `smartdiff-github.xml`. */
class GitHubTreeAdapter : CodeReviewTreeAdapter("GitHub pull request", "Pull Requests", "org.jetbrains.plugins.github.")

/** The changes of a GitLab merge request, registered by `smartdiff-gitlab.xml`. */
class GitLabTreeAdapter : CodeReviewTreeAdapter("GitLab merge request", "Merge Requests", "org.jetbrains.plugins.gitlab.")
