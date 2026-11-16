package io.github.agadek.smartdiff.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/** One stored mark: the file, the declaration path, the text it was marked on and when (outline/12). */
class ReviewedMark(
    var fileKey: String = "",
    var path: String = "",
    var fingerprint: String = "",
    var markedAt: Long = 0,
)

/** The workspace-file state of [ReviewedMarks]. */
class ReviewedMarksState {
    var marks: MutableList<ReviewedMark> = ArrayList()
}

/**
 * A set that compares change nodes by identity, not by their data-class equality: two distinct nodes can have the same
 * subject, text and ranges, but a mark is on one shown node.
 */
internal fun <T : Any> identitySetOf(elements: Iterable<T>): MutableSet<T> =
    Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(elements) }

internal fun <T : Any> identitySetOf(vararg elements: T): MutableSet<T> = identitySetOf(elements.asIterable())

/** Every markable node named by [item] and the rows under it (outline/12 details, "A row's nodes"). */
internal fun nodesOf(item: OutlineItem, markable: Set<ChangeNode>): Set<ChangeNode> {
    val nodes = identitySetOf<ChangeNode>()
    fun visit(row: OutlineItem) {
        row.node?.takeIf { it in markable }?.let(nodes::add)
        row.endpoints.forEach { if (it in markable) nodes += it }
        row.children.forEach(::visit)
    }
    visit(item)
    return nodes
}

/**
 * The marks that apply to one shown result, and the identity of every node a mark can be put on. [stale] holds marks
 * whose path matches a shown node with different text; they are removed before the result is shown (outline/12 R8).
 */
class Reviewed(
    val reviewed: Set<ChangeNode>,
    val markable: Set<ChangeNode>,
    val paths: Map<ChangeNode, String>,
    val fingerprints: Map<ChangeNode, String>,
    val stale: List<ReviewedMark> = emptyList(),
) {
    /** The markable nodes of a row, used for the mark actions and the reviewed label. */
    fun nodesOf(item: OutlineItem): Set<ChangeNode> = nodesOf(item, markable)

    /** Whether every node of a row is reviewed; a row with no markable nodes is never reviewed. */
    fun isReviewed(item: OutlineItem): Boolean {
        val nodes = nodesOf(item)
        return nodes.isNotEmpty() && nodes.all { it in reviewed }
    }

    companion object {
        val EMPTY = Reviewed(emptySet(), emptySet(), emptyMap(), emptyMap())
    }
}

/**
 * Reviewed marks of one project, kept in the workspace file so they stay in this IDE installation and out of version
 * control (outline/12 R9, R15). A mark is keyed by file, declaration path and a SHA-256 fingerprint of the node's text
 * on both sides, so a change to that text drops the mark (R8).
 */
@Service(Service.Level.PROJECT)
@State(name = "SmartDiffReviewedMarks", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ReviewedMarks(private val project: Project) : PersistentStateComponent<ReviewedMarksState> {

    @Volatile
    private var current = ReviewedMarksState()

    @Volatile
    private var warned = false

    override fun getState(): ReviewedMarksState = try {
        current
    } catch (e: Throwable) {
        warn("Could not read reviewed marks", e)
        ReviewedMarksState()
    }

    override fun loadState(state: ReviewedMarksState) {
        current = state
    }

    /** The file key of [file], or null when it has no path in the project (outline/12 R10). */
    fun fileKey(file: VirtualFile?): String? = keyOf(file?.path, project.basePath)

    /** The stored marks of one file, in no particular order. */
    fun marksFor(fileKey: String): List<ReviewedMark> = current.marks.filter { it.fileKey == fileKey }

    fun hasMarks(fileKey: String): Boolean = current.marks.any { it.fileKey == fileKey }

    /**
     * Reads the marks of [fileKey] against the shown [result], dropping a mark whose path matches a node with different
     * text. A mark whose path is absent is kept, since another diff of the file may show it again (outline/12 R8).
     */
    fun apply(fileKey: String, result: ComparisonResult, request: ComparisonRequest, whitespacePolicy: WhitespacePolicy): Reviewed {
        val marks = marksFor(fileKey)
        val reviewed = applyMarks(marks, result, request, whitespacePolicy)
        if (reviewed.stale.isNotEmpty()) {
            val keys = reviewed.stale.mapTo(HashSet()) { it.path to it.fingerprint }
            replace(current.marks.filterNot { mark -> mark.fileKey == fileKey && mark.path to mark.fingerprint in keys })
        }
        return reviewed
    }

    /** Adds [marks] to one file, keeping at most [LIMIT] marks and dropping the oldest first (outline/12 R9). */
    fun add(fileKey: String, marks: List<ReviewedMark>) {
        if (marks.isEmpty()) return
        val now = System.currentTimeMillis()
        val all = current.marks.toMutableList()
        for (mark in marks) {
            val candidate = ReviewedMark(fileKey, mark.path, mark.fingerprint, mark.markedAt.takeIf { it > 0 } ?: now)
            if (all.none { it.fileKey == candidate.fileKey && it.path == candidate.path && it.fingerprint == candidate.fingerprint }) {
                all += candidate
            }
        }
        replace(all)
        publish(fileKey)
    }

    /** Removes every mark of [paths] from one file, whatever its fingerprint (outline/12 R2, R3). */
    fun removePaths(fileKey: String, paths: Collection<String>) {
        if (paths.isEmpty()) return
        replace(current.marks.filterNot { it.fileKey == fileKey && it.path in paths })
        publish(fileKey)
    }

    /** Removes the exact marks [marks], used when a shown node's text no longer matches (outline/12 R8). */
    fun removeMarks(fileKey: String, marks: Collection<ReviewedMark>) {
        if (marks.isEmpty()) return
        val keys = marks.map { it.path to it.fingerprint }.toSet()
        replace(current.marks.filterNot { mark -> mark.fileKey == fileKey && mark.path to mark.fingerprint in keys })
        publish(fileKey)
    }

    /** Removes every mark of one file (outline/12 R11). */
    fun clear(fileKey: String) {
        if (!hasMarks(fileKey)) return
        replace(current.marks.filterNot { it.fileKey == fileKey })
        publish(fileKey)
    }

    /** Tells every outline of [fileKey] to rebuild from its kept result (outline/12 R14). */
    fun publish(fileKey: String) {
        project.messageBus.syncPublisher(CHANGED).changed(fileKey)
    }

    private fun replace(marks: List<ReviewedMark>) {
        val kept = if (marks.size > LIMIT) marks.sortedBy { it.markedAt }.takeLast(LIMIT) else marks
        current = ReviewedMarksState().also { it.marks = kept.toMutableList() }
    }

    private fun warn(message: String, e: Throwable) {
        if (!warned) {
            warned = true
            thisLogger().warn(message, e)
        }
    }

    /** Listens for marks added or removed, with the file key whose outlines must rebuild. */
    fun interface Listener {
        fun changed(fileKey: String)
    }

    companion object {
        /** At most this many marks per project; 10 000 are about 1.5 MB in the workspace file (outline/12 R9). */
        const val LIMIT = 10_000

        /** The path of the file region row, which has no declaration subject (outline/12 details). */
        const val FILE_REGION_PATH = "File region"

        @Topic.ProjectLevel
        val CHANGED: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): ReviewedMarks = project.service()

        /** A path relative to the project base with `/` separators; null for a file outside it or no base path. */
        fun keyOf(path: String?, basePath: String?): String? {
            if (path == null || basePath == null) return null
            val base = basePath.replace('\\', '/').trimEnd('/')
            val file = path.replace('\\', '/')
            val relative = when {
                file == base -> null
                file.startsWith("$base/") -> file.removePrefix("$base/")
                base.isEmpty() -> file.trimStart('/')
                else -> null
            } ?: return null
            return relative.takeIf { it.isNotEmpty() && it != "." && !it.startsWith("../") }
        }

        /** SHA-256, hex, of the node's old text, a NUL separator and its new text; a missing side contributes `-`. */
        fun fingerprint(node: ChangeNode, request: ComparisonRequest): String {
            val old = node.oldRange?.let { request.oldText.substring(it.start, it.end).toString() } ?: "-"
            val new = node.newRange?.let { request.newText.substring(it.start, it.end).toString() } ?: "-"
            val digest = MessageDigest.getInstance("SHA-256").digest((old + '\u0000' + new).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** The path of every node in a result: the titles of its ancestors and itself, top down, joined by `/`. */
        fun paths(result: ComparisonResult): Map<ChangeNode, String> {
            val paths = IdentityHashMap<ChangeNode, String>()
            result.fileRegion?.let { paths[it] = FILE_REGION_PATH }
            fun visit(node: ChangeNode, parent: String?) {
                val title = OutlineModel.title(node)
                val path = if (parent == null) title else "$parent/$title"
                paths[node] = path
                node.children.forEach { visit(it, path) }
            }
            result.changes.forEach { visit(it, null) }
            return paths
        }

        /** The markable nodes: every reported change, a node with grouped usages and the reported file region. */
        fun markable(
            result: ComparisonResult,
            oldText: CharSequence,
            newText: CharSequence,
            whitespacePolicy: WhitespacePolicy,
        ): Set<ChangeNode> = OutlineModel.markableNodes(result, oldText, newText, whitespacePolicy)

        /** The change nodes a relationship row frames, found by each endpoint's range on its own side. */
        fun endpointsOf(relationship: Relationship, result: ComparisonResult): List<ChangeNode> {
            val byOld = HashMap<TextRange, ChangeNode>()
            val byNew = HashMap<TextRange, ChangeNode>()
            fun visit(node: ChangeNode) {
                node.oldRange?.let { byOld.putIfAbsent(it, node) }
                node.newRange?.let { byNew.putIfAbsent(it, node) }
                node.children.forEach(::visit)
            }
            result.changes.forEach(::visit)
            val nodes = identitySetOf<ChangeNode>()
            relationship.old.forEach { byOld[it.range]?.let(nodes::add) }
            relationship.new.forEach { byNew[it.range]?.let(nodes::add) }
            return nodes.toList()
        }

        /** Applies [marks] to a result. Pure, so a controller with no file key can keep its marks locally. */
        fun applyMarks(
            marks: List<ReviewedMark>,
            result: ComparisonResult,
            request: ComparisonRequest,
            whitespacePolicy: WhitespacePolicy,
        ): Reviewed {
            val markable = markable(result, request.oldText, request.newText, whitespacePolicy)
            val paths = paths(result)
            val fingerprints = IdentityHashMap<ChangeNode, String>()
            val byPath = HashMap<String, MutableSet<String>>()
            val reviewed = identitySetOf<ChangeNode>()
            for (node in markable) {
                val path = paths[node] ?: continue
                val fingerprint = fingerprint(node, request)
                fingerprints[node] = fingerprint
                byPath.getOrPut(path, ::HashSet) += fingerprint
                if (marks.any { it.path == path && it.fingerprint == fingerprint }) reviewed += node
            }
            val stale = marks.filter { mark ->
                byPath[mark.path]?.let { mark.fingerprint !in it } == true
            }
            return Reviewed(reviewed, markable, paths, fingerprints, stale)
        }
    }
}
