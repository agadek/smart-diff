package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.merge.MergeTool
import com.intellij.diff.tools.util.side.ThreesideTextDiffViewer
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.ui.OnePixelSplitter
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import io.github.agadek.smartdiff.engine.registry.EngineSelection
import io.github.agadek.smartdiff.merge.JoinedResult
import io.github.agadek.smartdiff.merge.MergeComparison
import io.github.agadek.smartdiff.merge.MergeSide
import io.github.agadek.smartdiff.merge.MergeTexts
import io.github.agadek.smartdiff.ui.OutlineToggleTarget
import io.github.agadek.smartdiff.ui.OutlineVisibility
import io.github.agadek.smartdiff.ui.SmartDiffSettings
import io.github.agadek.smartdiff.ui.merge.MergeOutlineLayout.CONTAINER_TREE
import io.github.agadek.smartdiff.ui.merge.MergeOutlineLayout.GROUPED_BY_SIDE
import java.util.concurrent.CancellationException
import javax.swing.JComponent
import javax.swing.Timer

/** What the merge outline of one three-sided diff shows. */
sealed interface MergeOutlineState {
    data object Hidden : MergeOutlineState

    data object Loading : MergeOutlineState

    /** No installed engine supports the language, so no outline can run (merge outline spec 03 R1/R4). */
    data class NoEngine(val languageName: String) : MergeOutlineState

    /** The comparison itself failed before a joined result existed. */
    data class Failed(val reason: String) : MergeOutlineState

    data class Shown(
        val joined: JoinedResult,
        val summary: String,
        val items: List<MergeOutlineItem>,
        val hiddenCount: Int,
        val layout: MergeOutlineLayout,
        val leftName: MergeSideName,
        val rightName: MergeSideName,
        /** A status or base-mismatch warning shown above the tree; null when there is none. */
        val banner: String? = null,
    ) : MergeOutlineState
}

/**
 * The merge outline of one three-sided diff (merge outline spec 02, three-sided diff spec 03) or one merge window
 * (merge window spec 04): it runs the selected engine base → left and base → right through [MergeComparison], joins them,
 * builds the rows for the chosen layout and shows the selected row in the panes. It is disposed with the viewer.
 */
class MergeOutlineController private constructor(
    private val project: Project,
    private val component: JComponent,
    private val context: UserDataHolder,
    private val splitter: OnePixelSplitter,
    private val languageId: String,
    private val fileName: String?,
    private val baseDocument: Document,
    private val leftDocument: Document,
    private val rightDocument: Document,
    private val leftName: MergeSideName,
    private val rightName: MergeSideName,
    internal val selection: MergeOutlineSelection,
    internal val showsEngineChoice: Boolean,
    internal val showsRefresh: Boolean,
    private val mergeWindow: Boolean,
) : Disposable, OutlineToggleTarget {

    val panel = MergeOutlinePanel(this)

    var state: MergeOutlineState = MergeOutlineState.Hidden
        private set

    override var isOutlineVisible: Boolean = context.getUserData(OutlineVisibility.VISIBLE) ?: SmartDiffSettings.getInstance().showOutline
        private set

    private val layout: MergeOutlineLayout
        get() = if (SmartDiffSettings.getInstance().mergeGroupedBySide) GROUPED_BY_SIDE else CONTAINER_TREE

    private var running: Job? = null
    private var shownTexts: MergeTexts? = null
    private var shownJoined: JoinedResult? = null
    private var shownConflicts: List<TextRange>? = null
    private var disposed = false
    private val debounce = Timer(DEBOUNCE_MILLIS) { refresh() }.apply { isRepeats = false }
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) = documentChanged()
    }

    internal fun start() {
        component.putClientProperty(CONTROLLER, this)
        if (!mergeWindow) {
            baseDocument.addDocumentListener(documentListener, this)
            if (leftDocument !== baseDocument) leftDocument.addDocumentListener(documentListener, this)
            if (rightDocument !== leftDocument && rightDocument !== baseDocument) rightDocument.addDocumentListener(documentListener, this)
        } else {
            (selection as? MergeResultPane)?.onSelectionChanged = panel::refreshAbsence
        }
        applyVisibility()
    }

    override fun setOutlineVisible(visible: Boolean) {
        if (disposed || visible == isOutlineVisible) return
        isOutlineVisible = visible
        context.putUserData(OutlineVisibility.VISIBLE, visible)
        applyVisibility()
    }

    private fun applyVisibility() {
        if (isOutlineVisible) {
            splitter.secondComponent = panel
            refresh()
        } else {
            cancel()
            selection.clear()
            splitter.secondComponent = null
            show(MergeOutlineState.Hidden)
        }
        splitter.revalidate()
        splitter.repaint()
    }

    fun chooseEngine(engineId: EngineId) {
        val registry = EngineRegistry.getInstance(project)
        if (registry.select(languageId).selected?.id == engineId) return
        registry.choose(languageId, engineId)
        refresh()
    }

    fun selection(): EngineSelection = EngineRegistry.getInstance(project).select(languageId)

    /** Cancels any running comparison and starts a new one from the three documents. */
    fun refresh() {
        cancel()
        if (disposed || !isOutlineVisible) return
        val selection = selection()
        panel.showEngines(selection)
        val engine = selection.selected
        if (engine == null) {
            return show(MergeOutlineState.NoEngine(Language.findLanguageByID(languageId)?.displayName ?: languageId))
        }
        val texts = MergeTexts(
            base = baseDocument.immutableCharSequence,
            left = leftDocument.immutableCharSequence,
            right = rightDocument.immutableCharSequence,
            languageId = languageId,
            fileName = fileName,
            limits = SmartDiffSettings.getInstance().limits,
        )
        val job = Job(
            engine,
            baseDocument.modificationStamp,
            leftDocument.modificationStamp,
            rightDocument.modificationStamp,
            texts.limits.timeoutMillis,
        )
        running = job
        show(MergeOutlineState.Loading)
        val modality = ModalityState.stateForComponent(component)
        val executor = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService()
        ApplicationManager.getApplication().executeOnPooledThread {
            val joined: JoinedResult
            val conflicts: List<TextRange>?
            try {
                joined = MergeComparison.compare(engine, texts, job, executor)
                conflicts = if (mergeWindow) MergeWindowSupport.conflictRanges(texts.left, texts.base, texts.right) else null
            } catch (e: ProcessCanceledException) {
                ApplicationManager.getApplication().invokeLater({ finishTimedOut(job) }, modality)
                return@executeOnPooledThread
            } catch (e: CancellationException) {
                ApplicationManager.getApplication().invokeLater({ finishTimedOut(job) }, modality)
                return@executeOnPooledThread
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater(
                    { if (job === running) show(MergeOutlineState.Failed(e.message ?: e.javaClass.simpleName)) },
                    modality,
                )
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater({ finish(job, texts, joined, conflicts) }, modality)
        }
    }

    /** Runs on the EDT after a cancellation, so the panel is never updated off it. */
    private fun finishTimedOut(job: Job) {
        if (job !== running) return
        running = null
        val reason = if (job.timedOut) "timed out after ${job.timeoutMillis} ms" else "analysis was canceled"
        show(MergeOutlineState.Failed(reason))
    }

    private fun finish(job: Job, texts: MergeTexts, joined: JoinedResult, conflicts: List<TextRange>?) {
        if (disposed || job !== running || job.canceled) return
        running = null
        if (baseDocument.modificationStamp != job.baseStamp ||
            leftDocument.modificationStamp != job.leftStamp ||
            rightDocument.modificationStamp != job.rightStamp
        ) {
            // A document listener has already scheduled the next comparison.
            return
        }
        if (selection().selected?.id != job.engine.id) return refresh()
        showResult(texts, joined, conflicts)
    }

    /** Hides or shows changes of [type] from now on, rebuilding this outline without comparing again. */
    fun setHidden(type: io.github.agadek.smartdiff.ui.ChangeType, hide: Boolean) {
        val settings = SmartDiffSettings.getInstance()
        settings.hiddenChangeTypes = if (hide) settings.hiddenChangeTypes + type else settings.hiddenChangeTypes - type
        rebuild()
    }

    fun setHideBothSides(hide: Boolean) {
        SmartDiffSettings.getInstance().mergeHideBothSides = hide
        rebuild()
    }

    fun setLayout(layout: MergeOutlineLayout) {
        SmartDiffSettings.getInstance().mergeGroupedBySide = layout == GROUPED_BY_SIDE
        rebuild()
    }

    private fun rebuild() {
        val texts = shownTexts ?: return
        val joined = shownJoined ?: return
        showResult(texts, joined, shownConflicts)
    }

    private fun showResult(texts: MergeTexts, joined: JoinedResult, conflicts: List<TextRange>?) {
        val settings = SmartDiffSettings.getInstance()
        val result = MergeOutlineModel.build(
            joined,
            texts.base,
            texts.left,
            texts.right,
            leftName,
            rightName,
            layout,
            settings.hiddenChangeTypes,
            settings.mergeHideBothSides,
            conflicts,
        )
        shownTexts = texts
        shownJoined = joined
        shownConflicts = conflicts
        val banner = buildList {
            if (result.baseMismatch) add("the base was read differently for each side")
            problem(joined, MergeSide.LEFT, leftName)?.let(::add)
            problem(joined, MergeSide.RIGHT, rightName)?.let(::add)
        }.takeIf { it.isNotEmpty() }?.joinToString(" ")
        show(MergeOutlineState.Shown(joined, result.summary, result.items, result.hiddenCount, layout, leftName, rightName, banner))
        (selection as? MergeResultPane)?.prepare(result.baseRanges)
    }

    private fun problem(joined: JoinedResult, side: MergeSide, name: MergeSideName): String? {
        val result = joined.result(side)
        if (result.status == Status.ANALYZED || result.status == Status.PARTIAL) return null
        val reason = result.fallbackReason ?: result.status.name.lowercase()
        return "${name.display} was not analyzed: $reason"
    }

    /** The note under the tree for [item]: an unanalyzed or absent pane, or a result pane that no longer tracks it. */
    internal fun noteFor(item: MergeOutlineItem?): String {
        if (item == null || item.isGroup) return ""
        (selection as? MergeResultPane)?.let { return it.note(item) }
        val shown = state as? MergeOutlineState.Shown ?: return ""
        return buildList {
            if (!shown.joined.isAnalyzed(MergeSide.LEFT)) add("${shown.leftName.display} was not analyzed")
            if (!shown.joined.isAnalyzed(MergeSide.RIGHT)) add("${shown.rightName.display} was not analyzed")
            val missing = listOfNotNull(
                "left".takeIf { shown.joined.isAnalyzed(MergeSide.LEFT) && item.leftRange == null },
                "base".takeIf { item.baseRange == null },
                "right".takeIf { shown.joined.isAnalyzed(MergeSide.RIGHT) && item.rightRange == null },
            )
            if (missing.isNotEmpty()) add("Not present in the ${missing.joinToString(", ")} pane")
        }.joinToString("; ")
    }

    private fun documentChanged() {
        if (disposed || !isOutlineVisible) return
        cancel()
        debounce.restart()
    }

    private fun cancel() {
        debounce.stop()
        running?.canceled = true
        running = null
    }

    private fun show(state: MergeOutlineState) {
        if (state !is MergeOutlineState.Shown) {
            shownTexts = null
            shownJoined = null
            shownConflicts = null
        }
        this.state = state
        panel.show(state)
    }

    override fun dispose() {
        disposed = true
        cancel()
        selection.clear()
        selection.dispose()
        state = MergeOutlineState.Hidden
        panel.show(MergeOutlineState.Hidden)
        component.putClientProperty(CONTROLLER, null)
    }

    private class Job(
        val engine: ComparisonEngine,
        val baseStamp: Long,
        val leftStamp: Long,
        val rightStamp: Long,
        val timeoutMillis: Long,
    ) : Cancellation {
        @Volatile
        var canceled = false

        @Volatile
        var timedOut = false

        private val deadline = System.nanoTime() + timeoutMillis * 1_000_000

        override fun checkCanceled() {
            if (canceled) throw ProcessCanceledException()
            if (System.nanoTime() - deadline > 0) {
                timedOut = true
                throw ProcessCanceledException()
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MILLIS = 300
        private const val CONTROLLER = "smartdiff.merge.outline.controller"

        /** The merge outline attached to [viewer], or null if it has none. */
        fun of(viewer: FrameDiffTool.DiffViewer?): MergeOutlineController? =
            (viewer as? ThreesideTextDiffViewer)?.component?.getClientProperty(CONTROLLER) as? MergeOutlineController

        /** The merge outline attached to a merge window, or null if it has none. */
        fun of(viewer: MergeTool.MergeViewer?): MergeOutlineController? =
            viewer?.component?.getClientProperty(CONTROLLER) as? MergeOutlineController

        /** The outline of a three-sided diff, whose middle pane is the base. */
        internal fun threeSided(
            project: Project,
            viewer: ThreesideTextDiffViewer,
            context: DiffContext,
            splitter: OnePixelSplitter,
            languageId: String,
            fileName: String?,
            baseDocument: Document,
            leftDocument: Document,
            rightDocument: Document,
            titles: List<String?>,
        ): MergeOutlineController = MergeOutlineController(
            project,
            viewer.component,
            context,
            splitter,
            languageId,
            fileName,
            baseDocument,
            leftDocument,
            rightDocument,
            MergeSideNames.fromTitle(titles.getOrNull(0), "Left"),
            MergeSideNames.fromTitle(titles.getOrNull(2), "Right"),
            MergeOutlineNavigator(viewer),
            showsEngineChoice = true,
            showsRefresh = true,
            mergeWindow = false,
        )

        /** The outline of a merge window, whose middle pane is the editable result. */
        internal fun mergeWindow(
            project: Project,
            viewer: MergeTool.MergeViewer,
            context: UserDataHolder,
            splitter: OnePixelSplitter,
            languageId: String,
            fileName: String?,
            baseDocument: Document,
            leftDocument: Document,
            rightDocument: Document,
            leftName: MergeSideName,
            rightName: MergeSideName,
            result: MergeResultPane,
        ): MergeOutlineController = MergeOutlineController(
            project,
            viewer.component,
            context,
            splitter,
            languageId,
            fileName,
            baseDocument,
            leftDocument,
            rightDocument,
            leftName,
            rightName,
            result,
            showsEngineChoice = false,
            showsRefresh = false,
            mergeWindow = true,
        )
    }
}
