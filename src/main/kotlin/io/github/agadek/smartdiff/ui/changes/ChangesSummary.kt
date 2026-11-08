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
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesViewModifier
import com.intellij.openapi.vcs.changes.ChangesViewNodeAction
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.ChangesViewModelBuilder
import com.intellij.openapi.vcs.changes.ui.HoverIcon
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.FontUtil
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.registry.EngineDefaults
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import io.github.agadek.smartdiff.ui.OutlineItem
import io.github.agadek.smartdiff.ui.OutlineModel
import io.github.agadek.smartdiff.ui.OutlinePath
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import io.github.agadek.smartdiff.ui.appendItem
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.tree.TreeNode

/**
 * Adds each file's structural summary, such as `2 changed · 1 added`, after the file in the Local Changes and Commit
 * tree, and its outline rows under it (technical specification §9.4; roadmap: multi-file summary, levels 1 and 2).
 *
 * The platform calls this while it builds the tree, off the event dispatch thread. A file whose summary is not known
 * yet is left as it is and analyzed in the background; the tree is rebuilt once summaries arrive. Each file node with a
 * summary is replaced by a [SummaryChangeNode] for the same change, so selection, inclusion and actions stay the
 * platform's, with a [DeclarationNode] child for each top-level outline row.
 */
class ChangesSummaryModifier(private val project: Project) : ChangesViewModifier {

    override fun modifyTreeModelBuilder(builder: ChangesViewModelBuilder) {
        ChangesTreeActions.install()
        val settings = SmartDiffSettings.getInstance()
        val showSummary = settings.showChangesSummary
        val showDeclarations = settings.showChangesDeclarations
        if (!showSummary && !showDeclarations) {
            ChangesTreeSetup.schedule(project, suppressExpansion = false)
            return
        }
        val root = (builder as? TreeModelBuilder)?.myRoot ?: return
        val nodes = changeNodes(root)
        val summaries = ChangesSummaryService.getInstance(project).summaries(nodes.map { it.userObject })
        var hasDeclarationRows = false
        for ((node, summary) in nodes.zip(summaries)) {
            if (summary == null) continue
            val filtered = ChangesTreeFilter.filter(summary.items, settings.hiddenChangesTreeChangeTypes)
            val rows = if (showDeclarations) filtered.items else emptyList()
            if (!showSummary && rows.isEmpty()) continue
            val parent = node.parent as? ChangesBrowserNode<*> ?: continue
            val index = parent.getIndex(node)
            val text = if (showSummary) ChangesTreeFilter.summary(summary.text, filtered) else ""
            parent.remove(index)
            parent.insert(SummaryChangeNode(project, node, text).apply { rows.forEach { add(DeclarationNode(it)) } }, index)
            hasDeclarationRows = hasDeclarationRows || rows.isNotEmpty()
        }
        ChangesTreeSetup.schedule(project, suppressExpansion = hasDeclarationRows)
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
 * decorations such as partial-commit marks, followed by the summary in grey. It starts collapsed, so the tree shows
 * files until the user opens one. A row can carry a reason with no declaration rows, such as `not analyzed: too large`.
 */
class SummaryChangeNode internal constructor(
    project: Project,
    private val original: ChangesBrowserChangeNode,
    val summary: String,
) : ChangesBrowserChangeNode(project, original.userObject, null) {

    override fun render(renderer: ChangesBrowserNodeRenderer, selected: Boolean, expanded: Boolean, hasFocus: Boolean) {
        original.render(renderer, selected, expanded, hasFocus)
        if (summary.isNotEmpty()) renderer.append(FontUtil.spaceAndThinSpace() + summary, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    override fun shouldExpandByDefault(): Boolean = false
}

/**
 * A row of a file's outline under the file in the Changes tree, with its own rows as children, drawn as the outline
 * draws it.
 *
 * It is a helper node: the platform leaves it out of the selected and included changes, so selecting it selects no
 * change and the file's checkbox counts only the file. Opening it shows the file's diff with this row selected in the
 * outline ([DeclarationNodeAction]).
 */
class DeclarationNode internal constructor(val item: OutlineItem) : ChangesBrowserNode<OutlineItem>(item) {

    init {
        markAsHelperNode()
        item.children.forEach { add(DeclarationNode(it)) }
    }

    /** The file row this row is under, or null once it is out of the tree. */
    val fileNode: SummaryChangeNode? get() = generateSequence<TreeNode>(parent) { it.parent }.firstNotNullOfOrNull { it as? SummaryChangeNode }

    /** This row's place in the outline, to find it in the outline of the file's diff. */
    val path: OutlinePath get() = OutlinePath.of(generateSequence(this) { it.parent as? DeclarationNode }.map { it.item }.toList().asReversed())

    override fun render(renderer: ChangesBrowserNodeRenderer, selected: Boolean, expanded: Boolean, hasFocus: Boolean) =
        appendItem(renderer, item)

    override fun getTextPresentation(): String = item.label
}

/**
 * Opens a [DeclarationNode] on double-click or Enter: the file's diff, with the other changes of the tree next to it as
 * the platform's own diff action does, and the declaration selected in the outline once it is shown.
 *
 * [openDiff] is the platform action. When the action cannot be loaded, it opens the file's own diff without the
 * declaration context, so the click still shows a diff; it returns false only when no diff could be shown.
 */
class DeclarationNodeAction @JvmOverloads constructor(
    private val project: Project,
    private val openDiff: (Project, List<Change>, Int, Key<*>, Any) -> Boolean = ChangesDiff::show,
) : ChangesViewNodeAction {

    override fun createNodeHoverIcon(node: ChangesBrowserNode<*>): HoverIcon? = null

    override fun handleDoubleClick(node: ChangesBrowserNode<*>): Boolean {
        val declaration = node as? DeclarationNode ?: return false
        val change = declaration.fileNode?.userObject ?: return false
        val root = declaration.root as? ChangesBrowserNode<*> ?: return false
        val changes = VcsTreeModelData.allUnder(root).userObjects(Change::class.java).distinct()
        return openDiff(project, changes, changes.indexOf(change).coerceAtLeast(0), OutlinePath.KEY, declaration.path)
    }
}

/**
 * The structural summaries and outline rows of a project's changed files, computed one file at a time on a background
 * thread and cached by file, revisions, engine and analysis stamp. A file edited since its summary gets a new one.
 */
@Service(Service.Level.PROJECT)
class ChangesSummaryService(private val project: Project) {

    private sealed interface Entry {
        data object Pending : Entry

        /**
         * A computed summary. [retryAt] is when a timed-out summary may be retried; [retried] records that the one retry
         * has run, and [retrying] keeps a queued retry from being queued again while the row still shows the old reason.
         */
        data class Done(
            val summary: FileSummary?,
            val retryAt: Long = Long.MAX_VALUE,
            val retried: Boolean = false,
            val retrying: Boolean = false,
        ) : Entry
    }

    /**
     * What a summary was computed from: the file's paths, a version of each side, the engine, the confidence policy and
     * the analysis stamp. The stamp makes a result computed under older limits or an older engine layout land under an
     * older key instead of the current cache.
     */
    private data class Key(
        val oldPath: String,
        val newPath: String,
        val oldVersion: String,
        val newVersion: String,
        val engineId: String,
        val confidence: String,
        val analysis: AnalysisStamp,
    )

    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("SMART Diff Changes summary", 1)
    private val cache = ConcurrentHashMap<Key, Entry>()
    private var cacheStamp: AnalysisStamp? = null
    private val refreshScheduled = AtomicBoolean()

    /**
     * The summary of each of [changes], or null where it is not known yet or there is none: an added or deleted file, or
     * a language without an engine. A file the engine could not analyze (`FAILED` or `TOO_LARGE`) gets a summary that says
     * why, with no declaration rows. Unknown summaries are computed in the background, and the tree is refreshed when
     * they are ready. A timed-out summary stays visible while one retry is queued; a second timeout is kept. Files that
     * are no longer in [changes] are forgotten.
     */
    fun summaries(changes: List<Change>): List<FileSummary?> {
        val stamp = currentAnalysisStamp()
        useStamp(stamp)
        val requests = changes.map { request(it, stamp) }
        cache.keys.retainAll(requests.mapNotNullTo(HashSet()) { it?.key })
        return requests.map { request ->
            if (request == null) return@map null
            when (val entry = cache.putIfAbsent(request.key, Entry.Pending)) {
                null -> {
                    executor.execute { compute(request) }
                    null
                }
                Entry.Pending -> null
                is Entry.Done -> {
                    if (!entry.retrying && !entry.retried && entry.retryAt <= System.currentTimeMillis()) {
                        cache.replace(request.key, entry, entry.copy(retrying = true))
                        executor.execute { compute(request, retry = true) }
                    }
                    entry.summary
                }
            }
        }
    }

    /**
     * What the tree can show for [change] right now, without starting a comparison, evicting another entry or reading
     * revision content. [Fresh] carries the current summary; [Stale] means an older summary is still in the cache after
     * an edit; [Pending] means the current summary is being computed; [None] means there is no summary for the file.
     */
    internal fun snapshot(change: Change): SummarySnapshot {
        val request = request(change, currentAnalysisStamp()) ?: return SummarySnapshot.None
        return when (val entry = cache[request.key]) {
            is Entry.Done -> entry.summary?.let(SummarySnapshot::Fresh) ?: SummarySnapshot.None
            Entry.Pending -> if (hasOtherEntry(request.key)) SummarySnapshot.Stale else SummarySnapshot.Pending
            null -> if (hasOtherEntry(request.key)) SummarySnapshot.Stale else SummarySnapshot.None
        }
    }

    /** Whether the cache has an entry for the same paths under another key, such as the previous document version. */
    private fun hasOtherEntry(key: Key): Boolean =
        cache.keys.any { it != key && it.oldPath == key.oldPath && it.newPath == key.newPath }

    /** Applies the current analysis stamp; the cache is dropped when it differs from the stamp it was computed under. */
    fun settingsChanged() = useStamp(currentAnalysisStamp())

    /** Forgets every summary, for example after the analysis limits changed. */
    fun clear() {
        synchronized(cache) {
            cache.clear()
            cacheStamp = null
        }
    }

    private fun useStamp(stamp: AnalysisStamp) {
        synchronized(cache) {
            if (cacheStamp != stamp) {
                cache.clear()
                cacheStamp = stamp
            }
        }
    }

    /** What the current summaries depend on, as opposed to how the tree displays them. */
    private fun currentAnalysisStamp(): AnalysisStamp {
        val settings = SmartDiffSettings.getInstance()
        return AnalysisStamp(
            settings.analysisRevision,
            EngineDefaults.getInstance().revision,
            settings.confidence.format(),
        )
    }

    /** The values that change what a summary contains. A display toggle or filter does not move them. */
    internal data class AnalysisStamp(val settingsRevision: Long, val engineRevision: Long, val confidence: String)

    /** Waits until every summary requested so far is computed. */
    @TestOnly
    fun waitForSummaries() {
        executor.submit {}.get(30, TimeUnit.SECONDS)
    }

    private class Request(val key: Key, val change: Change, val languageId: String, val fileName: String)

    private fun request(change: Change, stamp: AnalysisStamp): Request? {
        val old = change.beforeRevision ?: return null
        val new = change.afterRevision ?: return null
        val fileName = new.file.name
        val languageId = (FileTypeRegistry.getInstance().getFileTypeByFileName(fileName) as? LanguageFileType)?.language?.id ?: return null
        val engine = EngineRegistry.getInstance(project).select(languageId).selected ?: return null
        val key = Key(
            old.file.path,
            new.file.path,
            version(old),
            version(new),
            engine.id.value,
            stamp.confidence,
            stamp,
        )
        return Request(key, change, languageId, fileName)
    }

    private fun compute(request: Request, retry: Boolean = false) {
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
        val current = cache[request.key] ?: return
        val retryable = summary?.retryable == true && !retry
        val next = Entry.Done(
            summary,
            retryAt = if (retryable) System.currentTimeMillis() + RETRY_DELAY_MILLIS else Long.MAX_VALUE,
            retried = retry || !retryable,
        )
        if (cache.replace(request.key, current, next)) scheduleRefresh()
    }

    /**
     * The summary of [request], or null when a side cannot be read. A comparison over the time limit becomes a failed
     * result; a file over a limit or not analyzed gets a reason and no declaration rows.
     */
    private fun summarize(request: Request): FileSummary? {
        val engine = EngineRegistry.getInstance(project).findEngine(EngineId(request.key.engineId)) ?: return null
        val oldText = content(request.change.beforeRevision) ?: return null
        val newText = content(request.change.afterRevision) ?: return null
        val settings = SmartDiffSettings.getInstance()
        val limits = settings.limits
        val comparison = ComparisonRequest(oldText, newText, request.languageId, request.fileName, limits, confidence = settings.confidence)
        val deadline = Deadline(limits.timeoutMillis)
        val result = try {
            compareSafely(engine, comparison, deadline)
        } catch (e: ProcessCanceledException) {
            timedOut(engine, deadline, limits) ?: throw e
        } catch (e: CancellationException) {
            timedOut(engine, deadline, limits) ?: throw e
        }
        val text = summary(result) ?: return null
        val items = when (result.status) {
            Status.ANALYZED, Status.PARTIAL ->
                OutlineModel.build(result, oldText, newText, SmartDiffSettings.getInstance().formattingOnlyInline)
            else -> emptyList()
        }
        return FileSummary(text, items, retryable = result.status == Status.FAILED && isTimedOut(result))
    }

    /** A failed result for the time limit, or null when the cancellation came from somewhere else, such as project disposal. */
    private fun timedOut(engine: ComparisonEngine, deadline: Deadline, limits: Limits): ComparisonResult? =
        if (deadline.timedOut) ComparisonResult.timedOut(engine, limits.timeoutMillis) else null

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

        /** Whether this deadline canceled the comparison, as opposed to a cancellation from somewhere else. */
        @Volatile
        var timedOut = false
            private set

        override fun checkCanceled() {
            if (System.nanoTime() - deadline > 0) {
                timedOut = true
                throw ProcessCanceledException()
            }
        }
    }

    companion object {
        const val REFRESH_DELAY_MILLIS = 300L

        /** How long a timed-out summary stays visible before its one retry is queued. */
        internal const val RETRY_DELAY_MILLIS = 1_000L

        fun getInstance(project: Project): ChangesSummaryService = project.service()

        /**
         * The version key of [revision]: its revision number, or for the current content of a local file its modification
         * stamp, so a summary is recomputed after every edit. A local file that is gone has no state to compare, so it
         * is just `local`.
         */
        internal fun version(revision: ContentRevision): String {
            // The current content of a local file has the null revision number; other revisions name their commit.
            if (revision.revisionNumber !== VcsRevisionNumber.NULL) return revision.revisionNumber.asString()
            // CurrentContentRevision hid an invalid file behind a null VirtualFile; a ContentRevision's path does not.
            val file = revision.file.virtualFile?.takeIf { it.isValid } ?: return "local"
            val stamp = FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: file.modificationStamp
            return "local:$stamp"
        }

        /**
         * The text shown after a file: the outline's summary line, such as `2 changed · 1 added`, or why the engine did
         * not analyze it. Null only for [Status.UNSUPPORTED], where the file gets no summary.
         */
        fun summary(result: ComparisonResult): String? = when (result.status) {
            Status.ANALYZED, Status.PARTIAL -> OutlineModel.summary(result).replaceFirstChar { it.lowercase() }
            Status.TOO_LARGE -> "not analyzed: too large"
            Status.FAILED -> when {
                isTimedOut(result) -> "not analyzed: timed out"
                result.fallbackReason?.contains("could not be parsed") == true -> "not analyzed: parse errors"
                else -> "not analyzed: analysis failed"
            }
            Status.UNSUPPORTED -> null
        }

        /** Whether [result] is the failed result for a comparison over its time limit. */
        fun isTimedOut(result: ComparisonResult): Boolean =
            result.status == Status.FAILED && result.fallbackReason?.startsWith(ComparisonResult.TIMEOUT_REASON) == true
    }
}

/**
 * What the Changes tree shows for a file: its summary line, such as `2 changed · 1 added`, or the reason it was not
 * analyzed, and its outline's top-level rows. The rows are the outline's own (§10) without either change type filter,
 * so the tree can apply its own filter and the summary can count every change. A row that carries a reason has no
 * declaration rows. A [retryable] summary stays visible while one retry is pending.
 */
class FileSummary(val text: String, val items: List<OutlineItem>, val retryable: Boolean = false)

/**
 * What [ChangesSummaryService.snapshot] finds for one change without computing anything: a summary for the current
 * document version, an older summary that an edit made stale, a summary still being computed, or no summary at all.
 */
internal sealed interface SummarySnapshot {
    data class Fresh(val summary: FileSummary) : SummarySnapshot

    data object Stale : SummarySnapshot

    data object Pending : SummarySnapshot

    data object None : SummarySnapshot
}

/** Rebuilds the Local Changes and Commit tree, which asks [ChangesSummaryModifier] again. */
internal fun refreshChangesView(project: Project) {
    project.messageBus.syncPublisher(ChangesViewModifier.TOPIC).updated()
}

/**
 * Shows or hides the summaries in every open project when the SMART Diff settings change. A change that only turns
 * following on or off applies the analysis stamp but does not rebuild the tree (settings/01 R14, R15).
 */
class ChangesSummarySettingsListener : SmartDiffSettings.Listener {
    private var lastTreeRevision = SmartDiffSettings.getInstance().treeRevision
    private var lastAnalysisRevision = SmartDiffSettings.getInstance().analysisRevision
    private var lastEngineRevision = EngineDefaults.getInstance().revision
    private var lastConfidence = SmartDiffSettings.getInstance().confidence.format()

    override fun settingsChanged() {
        val settings = SmartDiffSettings.getInstance()
        val engineRevision = EngineDefaults.getInstance().revision
        val confidence = settings.confidence.format()
        val projects = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
        for (project in projects) project.serviceIfCreated<ChangesSummaryService>()?.settingsChanged()
        // The analysis stamp above still notices engine, limit and layout changes made before this listener existed.
        val treeChanged = settings.treeRevision != lastTreeRevision
        val analysisChanged = settings.analysisRevision != lastAnalysisRevision ||
            engineRevision != lastEngineRevision || confidence != lastConfidence
        lastTreeRevision = settings.treeRevision
        lastAnalysisRevision = settings.analysisRevision
        lastEngineRevision = engineRevision
        lastConfidence = confidence
        if (!treeChanged && !analysisChanged) return
        for (project in projects) refreshChangesView(project)
    }
}
