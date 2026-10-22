package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesViewModifier
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.ChangesViewModelBuilder
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.FontUtil
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import io.github.agadek.smartdiff.ui.OutlineModel
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adds each file's structural summary, such as `2 changed · 1 added`, after the file in the Local Changes and Commit
 * tree (roadmap: multi-file summary, level 1).
 *
 * The platform calls this while it builds the tree, off the event dispatch thread. A file whose summary is not known
 * yet is left as it is and analyzed in the background; the tree is rebuilt once summaries arrive. Each file node with a
 * summary is replaced by a [SummaryChangeNode] for the same change, so selection, inclusion and actions stay the
 * platform's.
 */
class ChangesSummaryModifier(private val project: Project) : ChangesViewModifier {

    override fun modifyTreeModelBuilder(builder: ChangesViewModelBuilder) {
        if (!SmartDiffSettings.getInstance().showChangesSummary) return
        val root = (builder as? TreeModelBuilder)?.myRoot ?: return
        val nodes = changeNodes(root)
        val summaries = ChangesSummaryService.getInstance(project).summaries(nodes.map { it.userObject })
        for ((node, summary) in nodes.zip(summaries)) {
            if (summary == null) continue
            val parent = node.parent as? ChangesBrowserNode<*> ?: continue
            val index = parent.getIndex(node)
            parent.remove(index)
            parent.insert(SummaryChangeNode(project, node, summary), index)
        }
    }

    /** The file nodes of the tree, at most [MAX_FILES]; a node with children, such as a changed directory, is left alone. */
    private fun changeNodes(root: ChangesBrowserNode<*>): List<ChangesBrowserChangeNode> {
        val nodes = mutableListOf<ChangesBrowserChangeNode>()
        val all = root.depthFirstEnumeration()
        while (all.hasMoreElements() && nodes.size < MAX_FILES) {
            val node = all.nextElement()
            if (node is ChangesBrowserChangeNode && node !is SummaryChangeNode && node.isLeaf) nodes += node
        }
        return nodes
    }

    companion object {
        /** Files summarized in one tree; the rest of a larger change list shows the platform's rows only. */
        const val MAX_FILES = 500
    }
}

/**
 * A file row with its structural summary. It renders as the platform's own node for the change, which keeps that node's
 * decorations such as partial-commit marks, followed by the summary in grey.
 */
class SummaryChangeNode internal constructor(
    project: Project,
    private val original: ChangesBrowserChangeNode,
    val summary: String,
) : ChangesBrowserChangeNode(project, original.userObject, null) {

    override fun render(renderer: ChangesBrowserNodeRenderer, selected: Boolean, expanded: Boolean, hasFocus: Boolean) {
        original.render(renderer, selected, expanded, hasFocus)
        renderer.append(FontUtil.spaceAndThinSpace() + summary, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}

/**
 * The structural summaries of a project's changed files, computed one file at a time on a background thread and cached
 * by file, revisions and engine. A file edited since its summary gets a new one.
 */
@Service(Service.Level.PROJECT)
class ChangesSummaryService(private val project: Project) {

    private sealed interface Entry {
        data object Pending : Entry

        data class Done(val summary: String?) : Entry
    }

    /** What a summary was computed from: the file's paths, a version of each side and the engine. */
    private data class Key(val oldPath: String, val newPath: String, val oldVersion: String, val newVersion: String, val engineId: String)

    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("SMART Diff Changes summary", 1)
    private val cache = ConcurrentHashMap<Key, Entry>()
    private val refreshScheduled = AtomicBoolean()

    /**
     * The summary of each of [changes], or null where it is not known yet or there is none: an added or deleted file, a
     * language without an engine, or a file the engine could not analyze. Unknown summaries are computed in the
     * background, and the tree is refreshed when they are ready. Files that are no longer in [changes] are forgotten.
     */
    fun summaries(changes: List<Change>): List<String?> {
        val requests = changes.map(::request)
        cache.keys.retainAll(requests.mapNotNullTo(HashSet()) { it?.key })
        return requests.map { request ->
            if (request == null) return@map null
            when (val entry = cache.putIfAbsent(request.key, Entry.Pending)) {
                null -> {
                    executor.execute { compute(request) }
                    null
                }
                Entry.Pending -> null
                is Entry.Done -> entry.summary
            }
        }
    }

    /** Forgets every summary, for example after the analysis limits changed. */
    fun clear() = cache.clear()

    /** Waits until every summary requested so far is computed. */
    @TestOnly
    fun waitForSummaries() {
        executor.submit {}.get(30, TimeUnit.SECONDS)
    }

    private class Request(val key: Key, val change: Change, val languageId: String, val fileName: String)

    private fun request(change: Change): Request? {
        val old = change.beforeRevision ?: return null
        val new = change.afterRevision ?: return null
        val fileName = new.file.name
        val languageId = (FileTypeRegistry.getInstance().getFileTypeByFileName(fileName) as? LanguageFileType)?.language?.id ?: return null
        val engine = EngineRegistry.getInstance(project).select(languageId).selected ?: return null
        val key = Key(old.file.path, new.file.path, version(old), version(new), engine.id.value)
        return Request(key, change, languageId, fileName)
    }

    /** A local file's version is its unsaved or saved state, so a summary is recomputed after every edit. */
    private fun version(revision: ContentRevision): String {
        if (revision !is CurrentContentRevision) return revision.revisionNumber.asString()
        val file = revision.virtualFile ?: return "local"
        val stamp = FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: file.modificationStamp
        return "local:$stamp"
    }

    private fun compute(request: Request) {
        if (project.isDisposed) return
        val summary = try {
            summarize(request)
        } catch (e: ProcessCanceledException) {
            cache.remove(request.key)
            return
        } catch (e: CancellationException) {
            cache.remove(request.key)
            return
        } catch (e: Exception) {
            thisLogger().warn("Cannot summarize ${request.key.newPath}", e)
            null
        }
        // Only an entry still requested is stored, so a file that left the tree meanwhile is not kept.
        if (cache.replace(request.key, Entry.Pending, Entry.Done(summary))) scheduleRefresh()
    }

    private fun summarize(request: Request): String? {
        val engine = EngineRegistry.getInstance(project).findEngine(EngineId(request.key.engineId)) ?: return null
        val oldText = content(request.change.beforeRevision) ?: return null
        val newText = content(request.change.afterRevision) ?: return null
        val limits = SmartDiffSettings.getInstance().limits
        val comparison = ComparisonRequest(oldText, newText, request.languageId, request.fileName, limits)
        return summary(compareSafely(engine, comparison, Deadline(limits.timeoutMillis)))
    }

    private fun content(revision: ContentRevision?): String? = try {
        revision?.content
    } catch (e: VcsException) {
        null
    }

    /** Rebuilds the Changes tree once for the summaries that arrived within a short interval. */
    private fun scheduleRefresh() {
        if (!refreshScheduled.compareAndSet(false, true)) return
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            refreshScheduled.set(false)
            if (!project.isDisposed) refreshChangesView(project)
        }, REFRESH_DELAY_MILLIS, TimeUnit.MILLISECONDS)
    }

    /** Cancels a comparison that takes longer than the analysis time limit. */
    private class Deadline(timeoutMillis: Long) : Cancellation {
        private val deadline = System.nanoTime() + timeoutMillis * 1_000_000

        override fun checkCanceled() {
            if (System.nanoTime() - deadline > 0) throw ProcessCanceledException()
        }
    }

    companion object {
        const val REFRESH_DELAY_MILLIS = 300L

        fun getInstance(project: Project): ChangesSummaryService = project.service()

        /**
         * The text shown after a file: the outline's summary line, such as `2 changed · 1 added`, or null when the
         * engine did not analyze the file.
         */
        fun summary(result: ComparisonResult): String? {
            if (result.status != Status.ANALYZED && result.status != Status.PARTIAL) return null
            return OutlineModel.summary(result).replaceFirstChar { it.lowercase() }
        }
    }
}

/** Rebuilds the Local Changes and Commit tree, which asks [ChangesSummaryModifier] again. */
internal fun refreshChangesView(project: Project) {
    project.messageBus.syncPublisher(ChangesViewModifier.TOPIC).updated()
}

/** Shows or hides the summaries in every open project when the SMART Diff settings change. */
class ChangesSummarySettingsListener : SmartDiffSettings.Listener {
    override fun settingsChanged() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            project.serviceIfCreated<ChangesSummaryService>()?.clear()
            refreshChangesView(project)
        }
    }
}
