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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import io.github.agadek.smartdiff.engine.registry.EngineSelection
import io.github.agadek.smartdiff.references.ReferencePass
import java.awt.BorderLayout
import java.util.concurrent.CancellationException
import javax.swing.JPanel
import javax.swing.Timer

/** What the outline of one diff shows. */
sealed interface OutlineState {
    /** The outline is switched off for this diff; no comparison runs. */
    data object Hidden : OutlineState

    data object Loading : OutlineState

    /** No installed engine supports the language, for example plain text or one whose plugin was removed (§10). */
    data class NoEngine(val languageName: String) : OutlineState

    /** [hiddenCount] changes of the result are left out of [items] by the change type filter. */
    data class Shown(
        val result: ComparisonResult,
        val summary: String,
        val items: List<OutlineItem>,
        val hiddenCount: Int = 0,
        /** The grey note under the summary, such as `Resolving usages…`; null when there is none. */
        val usageNote: String? = null,
    ) : OutlineState
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
    internal val splitter: OnePixelSplitter,
    private val languageId: String,
    private val fileName: String?,
    /** The diff's new-side file, or its old-side file when the new side has none; null keeps the engine's result (R3). */
    private val file: VirtualFile?,
    private val oldDocument: Document,
    private val newDocument: Document,
    /** A row to select in the first result shown, when the diff was opened from the Changes tree (§9.4). */
    private var reveal: OutlinePath? = null,
) : Disposable, OutlineToggleTarget {

    val panel = OutlinePanel(this)
    internal val navigator = OutlineNavigator(viewer)
    internal val strip = OutlineStrip { setMinimised(false) }

    var state: OutlineState = OutlineState.Hidden
        private set

    /**
     * Whether the outline is shown; starts from the window's last choice, or from the setting in a new window, and is
     * shown for a diff opened to show a row of it.
     */
    override var isOutlineVisible: Boolean = reveal != null || (context.getUserData(OutlineVisibility.VISIBLE) ?: SmartDiffSettings.getInstance().showOutline)
        private set

    /**
     * Whether the visible outline is its strip (outline/02 R8 to R11). Starts from the last choice; a diff opened to show
     * a row of the outline opens the full panel, so the row is visible.
     */
    var isMinimised: Boolean = reveal == null && SmartDiffSettings.getInstance().outlineMinimised
        private set

    private var running: Job? = null

    /**
     * Test seam: the reference pass this controller runs; null uses [ReferencePass.run]. The outcome is still checked
     * for staleness, so a test can hold the pass or make it time out without changing production behavior.
     */
    internal var passRunner: ((ComparisonRequest, ComparisonResult, Cancellation) -> ReferencePass.Outcome)? = null

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
        if (isOutlineVisible) refresh()
    }

    /** Shows or hides the outline in this diff window only; the editors stay as they are (§10). */
    override fun setOutlineVisible(visible: Boolean) {
        if (disposed || visible == isOutlineVisible) return
        isOutlineVisible = visible
        context.putUserData(OutlineVisibility.VISIBLE, visible)
        applyVisibility()
        if (visible) refresh()
    }

    /**
     * Minimises the visible outline to its strip or restores the panel, without comparing again; the result stays
     * computed while minimised (outline/02 R8 to R11).
     */
    fun setMinimised(minimised: Boolean) {
        if (disposed || !isOutlineVisible || minimised == isMinimised) return
        isMinimised = minimised
        SmartDiffSettings.getInstance().outlineMinimised = minimised
        applyVisibility()
    }

    private fun applyVisibility() {
        if (!isOutlineVisible) {
            hideOutline()
        } else if (isMinimised) {
            splitter.secondComponent = null
            addStrip()
            strip.show(stripDetail(state))
        } else {
            removeStrip()
            splitter.secondComponent = panel
        }
        splitter.revalidate()
        splitter.repaint()
        viewer.component.revalidate()
        viewer.component.repaint()
    }

    private fun hideOutline() {
        cancel()
        navigator.clear()
        (viewer as? FilteringDiffViewer)?.hide(HiddenLines.NONE)
        removeStrip()
        splitter.secondComponent = null
        show(OutlineState.Hidden)
    }

    /**
     * Adds the strip to the viewer's right edge while the splitter keeps its proportion, so restoring the panel returns
     * it to the width the user last chose (outline/02 R9, R10, R14).
     */
    private fun addStrip() {
        val container = viewer.component as? JPanel ?: return
        if (strip.parent !== container) container.add(strip, BorderLayout.EAST)
    }

    private fun removeStrip() {
        val container = strip.parent as? JPanel ?: return
        container.remove(strip)
        container.revalidate()
        container.repaint()
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
        val engine = selection.selected
        if (engine == null) {
            // A previous result may have faded native changes; the message below promises the native diff shows every change.
            (viewer as? FilteringDiffViewer)?.hide(HiddenLines.NONE)
            return show(OutlineState.NoEngine(Language.findLanguageByID(languageId)?.displayName ?: languageId))
        }

        val request = ComparisonRequest(
            oldText = oldDocument.immutableCharSequence,
            newText = newDocument.immutableCharSequence,
            languageId = languageId,
            fileName = fileName,
            limits = SmartDiffSettings.getInstance().limits,
            confidence = SmartDiffSettings.getInstance().confidence,
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
        if (job.timedOut) ComparisonResult.timedOut(job.engine, job.timeoutMillis) else null

    /** Renders [result] unless it is stale: a document or the selected engine changed since the request (§9.2 step 3). */
    private fun finish(job: Job, request: ComparisonRequest, result: ComparisonResult) {
        if (disposed || job !== running || job.canceled) return
        if (oldDocument.modificationStamp != job.oldStamp || newDocument.modificationStamp != job.newStamp) {
            // A document listener has already scheduled the next comparison.
            return
        }
        if (selection().selected?.id != job.engine.id) return refresh()
        // The engine result is on screen before the pass starts, so a resolve never delays it (R2).
        if (ReferencePass.supports(languageId) && ReferencePass.isApplicable(project, file, result)) {
            showResult(request, result, note = RESOLVING)
            startPass(job, request, result)
        } else {
            showResult(request, result)
            running = null
        }
    }

    /** Runs the reference pass on the pooled thread and replaces the engine result when it returns (R1, R2, R16, R17). */
    private fun startPass(job: Job, request: ComparisonRequest, result: ComparisonResult) {
        val cancellable = job.passCancellation()
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = try {
                passRunner?.invoke(request, result, cancellable)
                    ?: ReferencePass.run(project, file, request, result, cancellable)
            } catch (e: ProcessCanceledException) {
                return@executeOnPooledThread
            } catch (e: CancellationException) {
                return@executeOnPooledThread
            }
            if (job.canceled) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater(
                { finishPass(job, request, outcome) },
                ModalityState.stateForComponent(viewer.component),
            )
        }
    }

    private fun finishPass(job: Job, request: ComparisonRequest, outcome: ReferencePass.Outcome) {
        if (disposed || job !== running || job.canceled) return
        if (oldDocument.modificationStamp != job.oldStamp || newDocument.modificationStamp != job.newStamp) return
        if (selection().selected?.id != job.engine.id) return refresh()
        running = null
        val note = if (outcome.notGrouped) USAGES_NOT_GROUPED else null
        showResult(request, outcome.result, note, keepSelection = true)
    }

    /** Hides or shows changes of [type] in every outline from now on, and rebuilds this one's rows without comparing again. */
    fun setHidden(type: ChangeType, hide: Boolean) {
        val settings = SmartDiffSettings.getInstance()
        settings.hiddenChangeTypes = if (hide) settings.hiddenChangeTypes + type else settings.hiddenChangeTypes - type
        val shown = state as? OutlineState.Shown ?: return
        val request = shownRequest ?: return
        showResult(request, shown.result, shown.usageNote, keepSelection = true)
    }

    private fun showResult(
        request: ComparisonRequest,
        result: ComparisonResult,
        note: String? = null,
        keepSelection: Boolean = false,
    ) {
        val settings = SmartDiffSettings.getInstance()
        val hidden = settings.hiddenChangeTypes
        val policy = whitespacePolicy(languageId)
        val selection = if (keepSelection) panel.selectedPath() else null
        val items = OutlineModel.build(result, request.oldText, request.newText, settings.formattingOnlyInline, hidden, policy)
        shownRequest = request
        show(
            OutlineState.Shown(
                result,
                OutlineModel.summary(result, hidden, request.oldText, request.newText, policy),
                items,
                OutlineModel.hiddenCount(result, hidden),
                note,
            ),
        )
        when {
            keepSelection && selection != null -> panel.reveal(selection)
            !keepSelection -> reveal?.let(panel::reveal)
        }
        if (!keepSelection) reveal = null
        (viewer as? FilteringDiffViewer)?.hide(HiddenLines.of(result, hidden, request.oldText, request.newText))
    }

    private fun documentChanged() {
        if (disposed || !isOutlineVisible) return
        cancel()
        navigator.releaseScrollSync()
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
        strip.show(stripDetail(state))
    }

    /**
     * What the strip says for [state]. A fallback keeps its reason and never uses the empty summary line, which would
     * claim there are no structural changes when the analysis did not run (outline/02 R9).
     */
    private fun stripDetail(state: OutlineState): String? = when (state) {
        OutlineState.Hidden -> null
        OutlineState.Loading -> "Comparing…"
        is OutlineState.NoEngine -> "No comparison engine supports ${state.languageName} files"
        is OutlineState.Shown -> when (state.result.status) {
            Status.ANALYZED, Status.PARTIAL -> state.summary
            Status.UNSUPPORTED -> fallbackDetail("Not supported", state)
            Status.TOO_LARGE -> fallbackDetail("Too large to analyze", state)
            Status.FAILED -> fallbackDetail("Analysis failed", state)
        }
    }

    private fun fallbackDetail(title: String, state: OutlineState.Shown): String =
        state.result.fallbackReason?.let { "$title: $it" } ?: title

    override fun dispose() {
        disposed = true
        cancel()
        navigator.clear()
        removeStrip()
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

        /** The pass's cancellation reads only the job's flag; the pass keeps its own 1 s clock (R17). */
        fun passCancellation(): Cancellation = Cancellation { if (canceled) throw ProcessCanceledException() }
    }

    companion object {
        const val DEBOUNCE_MILLIS = 300

        /** The grey note while the reference pass runs, and after it could not run (relationships/04 R2, R17). */
        const val RESOLVING = "Resolving usages…"
        const val USAGES_NOT_GROUPED = "usages not grouped"

        /** The whitespace policy the outline compares a rename-only declaration with, by language. */
        internal fun whitespacePolicy(languageId: String): WhitespacePolicy = when (languageId) {
            "JAVA" -> WhitespacePolicy.INSIGNIFICANT
            "kotlin" -> WhitespacePolicy.LINE_BREAKS_SIGNIFICANT
            "Scala" -> WhitespacePolicy.INDENTATION_SIGNIFICANT
            else -> WhitespacePolicy.SIGNIFICANT
        }

        /** The outline toggle's state for one diff window, shared by the viewers it shows one after another. */
        private const val CONTROLLER = "smartdiff.outline.controller"

        /** The outline attached to [viewer], or null if it has none. */
        fun of(viewer: FrameDiffTool.DiffViewer?): OutlineController? =
            (viewer as? TwosideTextDiffViewer)?.component?.getClientProperty(CONTROLLER) as? OutlineController
    }
}
