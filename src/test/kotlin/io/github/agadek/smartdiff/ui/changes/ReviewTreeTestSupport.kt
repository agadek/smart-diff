package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.NoneChangesGroupingFactory
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.vcsUtil.VcsUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.registry.ComparisonEngineFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A changes tree whose model is built from [nodes] the way the review trees build theirs, through a [TreeModelBuilder]
 * and `ChangesTree.updateTreeModel`, which saves the tree state, sets the model and restores the state (ADR 0013).
 */
internal class TestReviewTree(project: Project) : ChangesTree(project, false, false) {
    var nodes: List<Pair<FilePath, () -> ChangesBrowserNode<*>>> = emptyList()
    var rebuilds = 0

    /** Shows [changes] as plain change nodes, as the Git log's tree does. */
    fun showChanges(changes: List<Change>) {
        nodes = changes.map { change ->
            (change.afterRevision ?: change.beforeRevision)!!.file to { ChangesBrowserNode.createChange(project, change) as ChangesBrowserNode<*> }
        }
        rebuildTree()
    }

    override fun rebuildTree() {
        rebuilds++
        updateTreeModel(model())
    }

    override fun rebuildTree(strategy: TreeStateStrategy<*>) {
        rebuilds++
        updateTreeModel(model(), strategy)
    }

    private fun model(): javax.swing.tree.DefaultTreeModel {
        val builder = TreeModelBuilder(project, NoneChangesGroupingFactory)
        for ((path, node) in nodes) builder.insertChangeNode(path, builder.myRoot, node())
        return builder.build()
    }

    /** The file rows in tree order. */
    fun fileRows(): List<ChangesBrowserNode<*>> =
        root.depthFirstEnumeration().toList().filterIsInstance<ChangesBrowserNode<*>>()
            .filter { it !is DeclarationNode && (it is ReviewSummaryNode || it.isLeaf) && it !== root }
}

/** An adapter for [TestReviewTree] that takes each node's [Change] as the Git log adapter does. */
internal open class TestAdapter : ReviewTreeAdapter {
    override val name = "test"
    val opened = CopyOnWriteArrayList<Pair<Change, io.github.agadek.smartdiff.ui.OutlinePath>>()

    override fun accepts(tree: ChangesTree) = tree is TestReviewTree

    override fun changeOf(tree: ChangesTree, node: ChangesBrowserNode<*>): Change? = node.userObject as? Change

    override fun open(tree: ChangesTree, node: ReviewSummaryNode, path: io.github.agadek.smartdiff.ui.OutlinePath): Boolean {
        opened += node.change to path
        return true
    }
}

/** A revision with fixed [text] that records how often and on which thread it was read. */
internal class CountingRevision(
    private val text: String?,
    private val path: FilePath,
    private val number: String,
    private val onRead: () -> Unit = {},
) : ContentRevision {
    @Volatile
    var reads = 0
        private set

    @Volatile
    var readOnEdt = false
        private set

    override fun getContent(): String? {
        reads++
        if (javax.swing.SwingUtilities.isEventDispatchThread()) readOnEdt = true
        onRead()
        return text
    }

    override fun getFile(): FilePath = path

    override fun getRevisionNumber(): VcsRevisionNumber = object : VcsRevisionNumber {
        override fun asString() = number
        override fun compareTo(other: VcsRevisionNumber?) = asString().compareTo(other?.asString() ?: "")
    }
}

internal object ReviewTreeFixtures {
    val OLD = """
        class Cart {
            int total() {
                return 1;
            }
        }
    """.trimIndent()

    val NEW = """
        class Cart {
            int total() {
                return 2;
            }

            int count() {
                return 0;
            }
        }
    """.trimIndent()

    fun path(name: String): FilePath = VcsUtil.getFilePath("/repo/$name", false)

    fun modified(name: String, old: String = OLD, new: String = NEW, oldNumber: String = "1", newNumber: String = "2"): Change =
        Change(CountingRevision(old, path(name), oldNumber), CountingRevision(new, path(name), newNumber))

    fun maskEngines(disposable: Disposable, vararg engines: ComparisonEngine) {
        ExtensionTestUtil.maskExtensions(
            ComparisonEngineFactory.EP_NAME,
            engines.map { engine -> object : ComparisonEngineFactory { override fun create(project: Project?) = engine } },
            disposable,
        )
    }
}

/** Reports one changed class for any file, at once. */
internal class InstantReviewEngine : ComparisonEngine {
    override val id = EngineId("smartdiff.test.review.instant")
    override val displayName = "Instant review (test)"
    override val version = "1"

    override fun supports(languageId: String) = true

    override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult =
        ComparisonResult(
            id, version, Status.ANALYZED,
            changes = listOf(
                ChangeNode(
                    Subject.Declaration(DeclarationKind.CLASS, "X", null),
                    TextRange(0, 1).takeIf { request.oldText.isNotEmpty() },
                    TextRange(0, 1).takeIf { request.newText.isNotEmpty() },
                    when {
                        request.oldText.isEmpty() -> Presence.ADDED
                        request.newText.isEmpty() -> Presence.REMOVED
                        else -> Presence.MATCHED
                    },
                    content = Content.CHANGED,
                ),
            ),
        )
}
