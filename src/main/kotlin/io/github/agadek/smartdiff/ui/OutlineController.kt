package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.OnePixelSplitter
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import io.github.agadek.smartdiff.engine.registry.EngineSelection
import java.util.concurrent.CancellationException
import javax.swing.Timer

/** What the outline of one diff shows. */
sealed interface OutlineState {
    /** The outline is switched off for this diff; no comparison runs. */
    data object Hidden : OutlineState

    data object Loading : OutlineState

    /** No installed engine supports the language, for example plain text or one whose plugin was removed (§10). */
    data class NoEngine(val languageName: String) : OutlineState

    /** [hiddenCount] changes of the result are left out of [items] by the change type filter. */
    data class Shown(val result: ComparisonResult, val summary: String, val items: List<OutlineItem>, val hiddenCount: Int = 0) : OutlineState
}

/**
 * The outline of one two-sided diff viewer and its lifecycle (technical specification §9.2): it compares document
 * snapshots on a pooled thread, discards stale results, reruns after edits and engine changes, enforces the time limit
 * and lets go of every text and result when the viewer is disposed. All methods except the comparison itself run on the
 * event dispatch thread.
 */
class OutlineController internal constructor(
    private val project: Project,
    val viewer: TwosideTextDiffViewer,
    private val context: DiffContext,
    private val splitter: OnePixelSplitter,
    private val languageId: String,
    private val fileName: String?,
    private val oldDocument: Document,
    private val newDocument: Document,
    /** A row to select in the first result shown, when the diff was opened from the Changes tree (§9.4). */
    private var reveal: OutlinePath? = null,
) : Disposable {

    val panel = OutlinePanel(this)
    internal val navigator = OutlineNavigator(viewer)

    var state: OutlineState = OutlineState.Hidden
        private set

    /**
     * Whether the outline is shown; starts from the window's last choice, or from the setting in a new window, and is
     * shown for a diff opened to show a row of it.
     */
    var isOutlineVisible: Boolean = reveal != null || (context.getUserData(VISIBLE) ?: SmartDiffSettings.getInstance().showOutline)
        private set

    private var running: Job? = null

    /** The comparison behind the shown result, kept to rebuild the rows when the filter changes; null when none is shown. */
    private var shownRequest: ComparisonRequest? = null
    private var disposed = false
    private val debounce = Timer(DEBOUNCE_MILLIS) { refresh() }.apply { isRepeats = false }

    internal fun start() {
        viewer.component.putClientProperty(CONTROLLER, this)
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = documentChanged()
        }
        oldDocument.addDocumentListener(listener, this)
        if (newDocument !== oldDocument) newDocument.addDocumentListener(listener, this)
        applyVisibility()
    }

    /** Shows or hides the outline in this diff window only; the editors stay as they are (§10). */
    fun setOutlineVisible(visible: Boolean) {
        if (disposed || visible == isOutlineVisible) return
        isOutlineVisible = visible
        context.putUserData(VISIBLE, visible)
        applyVisibility()
    }

    private fun applyVisibility() {
        if (isOutlineVisible) {
            splitter.secondComponent = panel
            refresh()
        } else {
            cancel()
            navigator.clear()
            (viewer as? FilteringDiffViewer)?.hide(HiddenLines.NONE)
            splitter.secondComponent = null
            show(OutlineState.Hidden)
        }
        splitter.revalidate()
        splitter.repaint()
    }

    /** Stores [engineId] as the language's default, as the dropdown does (§4), and compares again with it. */
    fun chooseEngine(engineId: EngineId) {
        val registry = EngineRegistry.getInstance(project)
        if (registry.select(languageId).selected?.id == engineId) return
        registry.choose(languageId, engineId)
        refresh()
    }

    fun selection(): EngineSelection = EngineRegistry.getInstance(project).select(languageId)

    /** Cancels any running comparison and starts a new one from the current documents, if the outline is shown. */
    fun refresh() {
        cancel()
        if (disposed || !isOutlineVisible) return
        val selection = selection()
        panel.showEngines(selection)
        val engine = selection.selected ?: return show(OutlineState.NoEngine(Language.findLanguageByID(languageId)?.displayName ?: languageId))

        val request = ComparisonRequest(
            oldText = oldDocument.immutableCharSequence,
            newText = newDocument.immutableCharSequence,
            languageId = languageId,
            fileName = fileName,
            limits = SmartDiffSettings.getInstance().limits,
        )
        val job = Job(engine, oldDocument.modificationStamp, newDocument.modificationStamp, request.limits.timeoutMillis)
        running = job
        show(OutlineState.Loading)
        val modality = ModalityState.stateForComponent(viewer.component)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = compare(job, request) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ finish(job, request, result) }, modality)
        }
    }

    /** Runs the engine on a pooled thread; null when the comparison was canceled for a reason other than the time limit. */
    private fun compare(job: Job, request: ComparisonRequest): ComparisonResult? = try {
        compareSafely(job.engine, request, job)
    } catch (e: ProcessCanceledException) {
        timedOut(job)
    } catch (e: CancellationException) {
        timedOut(job)
    }

    private fun timedOut(job: Job): ComparisonResult? =
        if (job.timedOut) ComparisonResult.failed(job.engine, "timed out after ${job.timeoutMillis} ms") else null

    /** Renders [result] unless it is stale: a document or the selected engine changed since the request (§9.2 step 3). */
    private fun finish(job: Job, request: ComparisonRequest, result: ComparisonResult) {
        if (disposed || job !== running || job.canceled) return
        running = null
        if (oldDocument.modificationStamp != job.oldStamp || newDocument.modificationStamp != job.newStamp) {
            // A document listener has already scheduled the next comparison.
            return
        }
        if (selection().selected?.id != job.engine.id) return refresh()
        showResult(request, result)
    }

    /** Hides or shows changes of [type] in every outline from now on, and rebuilds this one's rows without comparing again. */
    fun setHidden(type: ChangeType, hide: Boolean) {
        val settings = SmartDiffSettings.getInstance()
        settings.hiddenChangeTypes = if (hide) settings.hiddenChangeTypes + type else settings.hiddenChangeTypes - type
        val shown = state as? OutlineState.Shown ?: return
        val request = shownRequest ?: return
        showResult(request, shown.result)
    }

    private fun showResult(request: ComparisonRequest, result: ComparisonResult) {
        val settings = SmartDiffSettings.getInstance()
        val hidden = settings.hiddenChangeTypes
        val items = OutlineModel.build(result, request.oldText, request.newText, settings.formattingOnlyInline, hidden)
        shownRequest = request
        show(OutlineState.Shown(result, OutlineModel.summary(result, hidden), items, OutlineModel.hiddenCount(result, hidden)))
        reveal?.let(panel::reveal)
        reveal = null
        (viewer as? FilteringDiffViewer)?.hide(HiddenLines.of(result, hidden, request.oldText, request.newText))
    }

    private fun documentChanged() {
        if (disposed || !isOutlineVisible) return
        cancel()
        (viewer as? FilteringDiffViewer)?.forgetHidden()
        debounce.restart()
    }

    private fun cancel() {
        debounce.stop()
        running?.canceled = true
        running = null
    }

    private fun show(state: OutlineState) {
        if (state !is OutlineState.Shown) shownRequest = null
        this.state = state
        panel.show(state)
    }

    override fun dispose() {
        disposed = true
        cancel()
        navigator.clear()
        state = OutlineState.Hidden
        panel.show(OutlineState.Hidden)
        viewer.component.putClientProperty(CONTROLLER, null)
    }

    /** One comparison: its engine, the document stamps it was made from, and its cancellation with the time limit (§9.2 step 5). */
    private class Job(val engine: ComparisonEngine, val oldStamp: Long, val newStamp: Long, val timeoutMillis: Long) : Cancellation {
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
        const val DEBOUNCE_MILLIS = 300

        /** The outline toggle's state for one diff window, shared by the viewers it shows one after another. */
        private val VISIBLE = Key.create<Boolean>("smartdiff.outline.visible")
        private const val CONTROLLER = "smartdiff.outline.controller"

        /** The outline attached to [viewer], or null if it has none. */
        fun of(viewer: FrameDiffTool.DiffViewer?): OutlineController? =
            (viewer as? TwosideTextDiffViewer)?.component?.getClientProperty(CONTROLLER) as? OutlineController
    }
}
