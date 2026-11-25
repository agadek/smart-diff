package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.DiffUtil
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import io.github.agadek.smartdiff.engine.api.AnalysisNote
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.LineEngineProvider
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.registry.EngineLanguage
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import io.github.agadek.smartdiff.engine.registry.EngineSelection
import io.github.agadek.smartdiff.references.ReferencePass
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.KeyboardFocusManager
import java.util.concurrent.CancellationException
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/** What the outline of one diff shows. */
sealed interface OutlineState {
    /** The outline is switched off for this diff; no comparison runs. */
    data object Hidden : OutlineState

    data object Loading : OutlineState

    /** No installed engine supports the language, for example plain text or one whose plugin was removed (§10). */
    data class NoEngine(val languageName: String) : OutlineState

    /** The line comparison found no moved block, so the native diff shows every change (engines/04 R14). */
    data object NoMovedLines : OutlineState

    /** [hiddenCount] changes of the result are left out of [items] by the change type filter. */
    data class Shown(
        val result: ComparisonResult,
        val summary: String,
        val items: List<OutlineItem>,
        val hiddenCount: Int = 0,
        /** The grey note under the summary, such as `Resolving usages…`; null when there is none. */
        val usageNote: String? = null,
        /** The policy the compare used for `usages renamed`; Scala 2 and Scala 3 differ (R10). */
        val whitespacePolicy: WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT,
    ) : OutlineState
}

/**
 * The outline of one diff viewer and its lifecycle (technical specification §9.2): it compares document snapshots on a
 * pooled thread, discards stale results, reruns after edits and engine changes, enforces the time limit and lets go of
 * every text and result when the viewer is disposed. All methods except the comparison itself run on the event dispatch
 * thread.
 */
class OutlineController internal constructor(
    private val project: Project,
    val viewer: FrameDiffTool.DiffViewer,
    private val context: DiffContext,
    internal val splitter: OnePixelSplitter,
    private val languageId: String,
    private val fileName: String?,
    /** The diff's new-side file, or its old-side file when the new side has none; null keeps the engine's result (R3). */
    private val file: VirtualFile?,
    internal val oldDocument: Document,
    internal val newDocument: Document,
    internal val oldEditor: Editor?,
    internal val newEditor: Editor?,
    /** A row to select in the first result shown, when the diff was opened from the Changes tree (§9.4). */
    private var reveal: OutlinePath? = null,
    /** True only for a two-sided diff: it may use the line comparison when no engine supports the language (engines/04). */
    private val lineComparison: Boolean = false,
) : Disposable, OutlineToggleTarget {

    val panel = OutlinePanel(this)
    private val twosideViewer = viewer as? TwosideTextDiffViewer
    internal val navigator = OutlineNavigator(
        this,
        oldEditor,
        newEditor,
        { twosideViewer?.syncScrollSupport },
        { twosideViewer?.syncScrollSupport?.scrollable?.isSyncScrollEnabled == true },
    )
    internal val strip = OutlineStrip { setMinimised(false) }
    internal val usageHighlights = RelationshipHighlights(viewer)
    internal val movedMarkers = MovedCodeMarkers(viewer, ::revealFromMarker)

    var state: OutlineState = OutlineState.Hidden
        private set

    /** The marks that apply to the shown result, and the identity of every node a mark can be put on (outline/12). */
    internal var reviewed: Reviewed = Reviewed.EMPTY
        private set

    /** A file path inside the project, or null when marks must stay local to this open diff (outline/12 R10). */
    internal val fileKey: String? = ReviewedMarks.keyOf(file?.path, project.basePath)

    /** Marks of a diff without a file key; they go away with the controller (outline/12 R10). */
    private val localMarks = mutableListOf<ReviewedMark>()

    private val marks = ReviewedMarks.getInstance(project)

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
    private var settingsSubscribed = false

    /**
     * Test seam: the reference pass this controller runs; null uses [ReferencePass.run]. The outcome is still checked
     * for staleness, so a test can hold the pass or make it time out without changing production behavior.
     */
    internal var passRunner: ((ComparisonRequest, ComparisonResult, Cancellation) -> ReferencePass.Outcome)? = null

    /** The comparison behind the shown result, kept to rebuild the rows when the filter changes; null when none is shown. */
    private var shownRequest: ComparisonRequest? = null
    private var disposed = false
    private val debounce = Timer(DEBOUNCE_MILLIS) { refresh() }.apply { isRepeats = false }

    private data class DisplaySnapshot(
        val hiddenChangeTypes: Set<ChangeType>,
        val formattingOnlyInline: Boolean,
        val markCommentOnly: Boolean,
        val rewriteOnly: Set<EquivalenceRule>,
        val hideReviewed: Boolean,
        val markMovedCode: Boolean,
        val highlightChangedTokens: Boolean,
        val outlineView: OutlineView,
        val hiddenRelationshipKinds: Set<RelationshipKind>,
    )

    private data class AnalysisSnapshot(
        val engineId: EngineId?,
        val limits: Limits,
        val useStructureViewFallback: Boolean,
        val compareLinesWithoutEngine: Boolean,
        val treeSitterLanguages: Boolean,
        val detectCodeMoves: Boolean,
    )

    private var displaySnapshot: DisplaySnapshot? = null
    private var analysisSnapshot: AnalysisSnapshot? = null

    /** The caret side followed by [caretDebounce] (outline/07). */
    internal enum class CaretSide { OLD, NEW }

    private var lastCaretSide: CaretSide? = null
    private var lastCaretOffset: Int = 0
    /** A caret move was skipped because the result was stale, pending or running (outline/07 R8). */
    private var caretFollowPending = false
    @TestOnly
    internal var outlineFocusedOverride: Boolean? = null
    @TestOnly
    internal var editorFocusedOverride: Boolean? = null
    /** Nested while the outline scrolls the editors itself, so the resulting caret move is not followed (R10). */
    private var suppressCaretFollow = 0
    private val caretDebounce = Timer(CARET_DEBOUNCE_MILLIS) { followLastCaret() }.apply { isRepeats = false }

    /** Runs [action] without following the caret moves it causes (outline/07 R10). */
    internal fun withSuppressedCaretFollow(action: () -> Unit) {
        suppressCaretFollow++
        try {
            caretDebounce.stop()
            caretFollowPending = false
            lastCaretSide = null
            action()
        } finally {
            suppressCaretFollow--
        }
    }

    /**
     * Expands the collapsed unchanged fragments of [item] on both sides and scrolls each editor to the declaration's first
     * line on its side, keeping the outline's selection (outline/14 R3 to R10). It never compares again and never rebuilds
     * the rows; the scroll runs with caret follow suppressed and synchronized scrolling paused, as a selection's scroll does.
     */
    fun expandDeclaration(item: OutlineItem) {
        if (item.isGroup || item.kind == ItemKind.FILE_REGION) return
        val oldRanges = ExpandDeclaration.oldRanges(item)
        val newRanges = ExpandDeclaration.newRanges(item)
        // Expanding changes the frames' size, so the selection's scroll hold is stale; the frames and selection stay.
        navigator.releaseScrollSync()
        withSuppressedCaretFollow {
            navigator.withSyncDisabled {
                ExpandDeclaration.expand(oldEditor, newEditor, item)
                scrollToFirst(oldEditor, oldRanges.firstOrNull())
                scrollToFirst(newEditor, newRanges.firstOrNull())
            }
        }
    }

    private fun scrollToFirst(editor: Editor?, range: TextRange?) {
        if (editor == null || range == null) return
        val line = editor.document.getLineNumber(range.start.coerceIn(0, editor.document.textLength))
        DiffUtil.scrollEditor(editor, line, false)
    }

    internal fun start() {
        viewer.component.putClientProperty(CONTROLLER, this)
        // The editors' Expand Declaration action finds this controller through its user data (outline/14 R2).
        oldEditor?.putUserData(EDITOR_KEY, this)
        newEditor?.putUserData(EDITOR_KEY, this)
        if (!settingsSubscribed) {
            settingsSubscribed = true
            val settings = SmartDiffSettings.getInstance()
            displaySnapshot = displaySnapshot(settings)
            analysisSnapshot = analysisSnapshot(settings)
            ApplicationManager.getApplication().messageBus.connect(this)
                .subscribe(SmartDiffSettings.CHANGED, SmartDiffSettings.Listener { settingsChanged() })
            project.messageBus.connect(this).subscribe(ReviewedMarks.CHANGED, ReviewedMarks.Listener { key ->
                if (key == fileKey && !disposed) rebuild()
            })
        }
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = documentChanged()
        }
        oldDocument.addDocumentListener(listener, this)
        if (newDocument !== oldDocument) newDocument.addDocumentListener(listener, this)
        oldEditor?.caretModel?.addCaretListener(caretListener(CaretSide.OLD), this)
        newEditor?.caretModel?.addCaretListener(caretListener(CaretSide.NEW), this)
        applyVisibility()
        if (isOutlineVisible) refresh()
    }

    private fun displaySnapshot(settings: SmartDiffSettings): DisplaySnapshot = DisplaySnapshot(
        settings.hiddenChangeTypes,
        settings.formattingOnlyInline,
        settings.markCommentOnly,
        settings.rewriteOnlyRules,
        settings.hideReviewed,
        settings.markMovedCode,
        settings.highlightChangedTokens,
        settings.outlineView,
        settings.hiddenRelationshipKinds,
    )

    private fun analysisSnapshot(settings: SmartDiffSettings): AnalysisSnapshot = AnalysisSnapshot(
        engineFor(selection())?.id,
        settings.limits,
        settings.useStructureViewFallback,
        settings.compareLinesWithoutEngine,
        settings.treeSitterLanguages,
        settings.detectCodeMoves,
    )

    /**
     * The engine this outline compares with (engines/04 R1 to R4, R19): a selected engine always wins, then the line
     * comparison when the setting is on and this is a two-sided diff, and otherwise null for the `NoEngine` message.
     * Both [refresh] and the staleness checks call it, so a setting change drops an older result.
     */
    internal fun engineFor(selection: EngineSelection): ComparisonEngine? {
        selection.selected?.let { return it }
        if (lineComparison && SmartDiffSettings.getInstance().compareLinesWithoutEngine) {
            return ApplicationManager.getApplication().getService(LineEngineProvider::class.java).engine()
        }
        return null
    }

    private fun settingsChanged() {
        if (disposed) return
        val settings = SmartDiffSettings.getInstance()
        val newAnalysis = analysisSnapshot(settings)
        val newDisplay = displaySnapshot(settings)
        if (!isOutlineVisible) {
            analysisSnapshot = newAnalysis
            displaySnapshot = newDisplay
            return
        }
        val analysisChanged = newAnalysis != analysisSnapshot
        val displayChanged = newDisplay != displaySnapshot
        if (analysisChanged) {
            analysisSnapshot = newAnalysis
            displaySnapshot = newDisplay
            refresh()
            return
        }
        if (displayChanged && state is OutlineState.Shown) {
            displaySnapshot = newDisplay
            rebuild()
            return
        }
        if (displayChanged) {
            displaySnapshot = newDisplay
        }
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

    /** Selects the row of a clicked moved marker, restoring the panel first when it is minimised (outline/13 R7, R8). */
    private fun revealFromMarker(path: OutlinePath) {
        if (disposed || state !is OutlineState.Shown) return
        if (isMinimised) setMinimised(false)
        panel.reveal(path)
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
        usageHighlights.clear()
        movedMarkers.clear()
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
        val settings = SmartDiffSettings.getInstance()
        analysisSnapshot = analysisSnapshot(settings)
        refresh()
    }

    fun selection(): EngineSelection = EngineRegistry.getInstance(project).select(languageId)

    /** Cancels any running comparison and starts a new one from the current documents, if the outline is shown. */
    fun refresh() {
        cancel()
        if (disposed || !isOutlineVisible) return
        val selection = selection()
        panel.showEngines(selection)
        val engine = engineFor(selection)
        if (engine == null) {
            // A previous result may have faded native changes; the message below promises the native diff shows every change.
            (viewer as? FilteringDiffViewer)?.hide(HiddenLines.NONE)
            usageHighlights.clear()
            return show(OutlineState.NoEngine(Language.findLanguageByID(languageId)?.displayName ?: EngineLanguage.displayName(languageId) ?: languageId))
        }

        val settings = SmartDiffSettings.getInstance()
        val request = ComparisonRequest(
            oldText = oldDocument.immutableCharSequence,
            newText = newDocument.immutableCharSequence,
            languageId = languageId,
            fileName = fileName,
            limits = settings.limits,
            confidence = settings.confidence,
            useStructureViewFallback = settings.useStructureViewFallback,
            detectCodeMoves = settings.detectCodeMoves,
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
        if (engineFor(selection())?.id != job.engine.id) return refresh()
        // The engine result is on screen before the pass starts, so a resolve never delays it (R2). Only the cheap
        // checks run here, on the EDT; the pass reads the project file index on its pooled thread (R3).
        if (ReferencePass.supports(languageId) && ReferencePass.canRun(project, file, result)) {
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
        if (engineFor(selection())?.id != job.engine.id) return refresh()
        running = null
        val note = if (outcome.notGrouped) USAGES_NOT_GROUPED else null
        showResult(request, outcome.result, note, keepSelection = true, policy = outcome.whitespacePolicy)
    }

    /** Hides or shows changes of [type] in every outline from now on, and rebuilds this one's rows without comparing again. */
    fun setHidden(type: ChangeType, hide: Boolean) {
        val settings = SmartDiffSettings.getInstance()
        settings.hiddenChangeTypes = if (hide) settings.hiddenChangeTypes + type else settings.hiddenChangeTypes - type
        rebuild()
    }

    /** Turns comment-only labels on or off in every outline, and rebuilds this one's rows without comparing again (outline/11 R2, R12). */
    fun setMarkCommentOnly(mark: Boolean) {
        val settings = SmartDiffSettings.getInstance()
        if (settings.markCommentOnly == mark) return
        settings.markCommentOnly = mark
        rebuild()
    }

    /** Turns rewrite-only labels on or off in every outline, and rebuilds this one's rows without comparing again (declarations/08 R2, R17). */
    fun setMarkRewriteOnly(mark: Boolean) {
        val settings = SmartDiffSettings.getInstance()
        if (settings.markRewriteOnly == mark) return
        settings.markRewriteOnly = mark
        rebuild()
    }

    /** Hides or shows reviewed changes in every outline, without comparing again (outline/12 R12). */
    fun setHideReviewed(hide: Boolean) {
        val settings = SmartDiffSettings.getInstance()
        if (settings.hideReviewed == hide) return
        settings.hideReviewed = hide
        rebuild()
    }

    /** Whether this file has any mark, for the Clear Reviewed Marks action (outline/12 R11). */
    fun hasReviewedMarks(): Boolean = fileKey?.let(marks::hasMarks) ?: localMarks.isNotEmpty()

    /** Marks or unmarks the selected row's nodes, keeping the selection and never comparing again (outline/12 R2, R14). */
    fun markSelected(mark: Boolean) {
        panel.selectedItem()?.let { setReviewed(it, mark) }
    }

    /** Space toggles a row: all its nodes reviewed unmarks them, otherwise they are marked (outline/12 R2). */
    fun toggleReviewed(item: OutlineItem) {
        val nodes = reviewed.nodesOf(item)
        if (nodes.isEmpty()) return
        setReviewed(item, !nodes.all { it in reviewed.reviewed })
    }

    private fun setReviewed(item: OutlineItem, mark: Boolean) {
        val request = shownRequest ?: return
        val nodes = reviewed.nodesOf(item)
        if (nodes.isEmpty()) return
        val entries = nodes.mapNotNull { node ->
            val path = reviewed.paths[node] ?: return@mapNotNull null
            val fingerprint = reviewed.fingerprints[node] ?: return@mapNotNull null
            ReviewedMark(path = path, fingerprint = fingerprint, markedAt = System.currentTimeMillis())
        }
        if (fileKey == null) {
            if (mark) {
                for (entry in entries) {
                    if (localMarks.none { it.path == entry.path && it.fingerprint == entry.fingerprint }) localMarks += entry
                }
            } else {
                val paths = entries.mapTo(HashSet()) { it.path }
                localMarks.removeAll { it.path in paths }
            }
            rebuild()
        } else if (mark) {
            marks.add(fileKey, entries)
        } else {
            marks.removePaths(fileKey, entries.mapTo(HashSet()) { it.path })
        }
    }

    /** Removes every mark of this file (outline/12 R11). */
    fun clearReviewedMarks() {
        if (fileKey == null) {
            if (localMarks.isEmpty()) return
            localMarks.clear()
            rebuild()
        } else {
            marks.clear(fileKey)
        }
    }

    /** Switches this outline and every later one to [view], without comparing the file again (relationships/06 R1, R2). */
    fun setOutlineView(view: OutlineView) {
        val settings = SmartDiffSettings.getInstance()
        if (settings.outlineView == view) return
        settings.outlineView = view
        rebuild()
    }

    /** Hides or shows relationships of [kind] in the refactorings view, without comparing the file again (R14). */
    fun setHiddenRelationshipKind(kind: RelationshipKind, hide: Boolean) {
        val settings = SmartDiffSettings.getInstance()
        settings.hiddenRelationshipKinds = if (hide) settings.hiddenRelationshipKinds + kind else settings.hiddenRelationshipKinds - kind
        rebuild()
    }

    /** Rebuilds the shown rows from the kept result, reading the marks without dropping stale ones (outline/12 R8). */
    private fun rebuild() {
        val shown = state as? OutlineState.Shown ?: return
        val request = shownRequest ?: return
        showResult(
            request, shown.result, shown.usageNote, keepSelection = true,
            policy = shown.whitespacePolicy, dropStale = false,
        )
    }

    /**
     * Shows [result]. [dropStale] is false only for a rebuild from the kept result, which reads the marks without
     * dropping those whose text no longer matches; a freshly compared result drops them (outline/12 R8).
     */
    private fun showResult(
        request: ComparisonRequest,
        result: ComparisonResult,
        note: String? = null,
        keepSelection: Boolean = false,
        policy: WhitespacePolicy? = null,
        dropStale: Boolean = true,
    ) {
        // The file region alone would repeat the native diff, and a line comparison with no moved block reports no
        // structural change; show the message in place of an empty tree (engines/04 R14).
        if (result.analysisNote == AnalysisNote.LINES && result.changes.isEmpty()) {
            (viewer as? FilteringDiffViewer)?.hide(HiddenLines.NONE)
            show(OutlineState.NoMovedLines)
            return
        }
        val settings = SmartDiffSettings.getInstance()
        val hidden = settings.hiddenChangeTypes
        val markCommentOnly = settings.markCommentOnly
        val rewriteOnly = settings.rewriteOnlyRules
        val hideReviewed = settings.hideReviewed
        val whitespacePolicy = policy ?: whitespacePolicy(languageId)
        val selection = if (keepSelection) panel.selectedPath() else null
        val view = settings.outlineView
        reviewed = if (fileKey == null) {
            val applied = ReviewedMarks.applyMarks(localMarks, result, request, whitespacePolicy)
            if (dropStale && applied.stale.isNotEmpty()) localMarks.removeAll(applied.stale)
            applied
        } else {
            marks.apply(fileKey, result, request, whitespacePolicy, dropStale)
        }
        val reviewedNodes = reviewed.reviewed
        val items = if (view == OutlineView.REFACTORINGS) {
            RefactoringsModel.build(
                result, request.oldText, request.newText, settings.formattingOnlyInline, hidden,
                settings.hiddenRelationshipKinds, whitespacePolicy, markCommentOnly, reviewedNodes, hideReviewed, rewriteOnly,
            )
        } else {
            OutlineModel.build(
                result, request.oldText, request.newText, settings.formattingOnlyInline, hidden, whitespacePolicy,
                markCommentOnly, reviewedNodes, hideReviewed, rewriteOnly,
            )
        }
        val revealApplied = !keepSelection && reveal != null
        shownRequest = request
        show(
            OutlineState.Shown(
                result,
                OutlineModel.summary(result, hidden, request.oldText, request.newText, whitespacePolicy, markCommentOnly, reviewedNodes, hideReviewed, rewriteOnly),
                items,
                OutlineModel.hiddenCount(result, hidden, markCommentOnly, reviewedNodes, hideReviewed, rewriteOnly),
                note,
                whitespacePolicy,
            ),
        )
        if (fileKey != null && dropStale && reviewed.stale.isNotEmpty()) marks.publish(fileKey)
        if (settings.markMovedCode) {
            movedMarkers.show(MovedCodeMarkers.targets(items))
        } else {
            movedMarkers.clear()
        }
        if (view == OutlineView.REFACTORINGS) {
            usageHighlights.show(result, settings.hiddenRelationshipKinds)
        } else {
            usageHighlights.clear()
        }
        when {
            keepSelection && selection != null -> panel.reveal(selection, scrollEditors = false)
            !keepSelection -> reveal?.let(panel::reveal)
        }
        if (!keepSelection) reveal = null
        // declarations/10 R1, R11, R18: the changed runs, built only while the setting is on.
        val runs = if (settings.highlightChangedTokens) ChangedRuns.of(result, request.oldText, request.newText) else ChangedRuns.NONE
        (viewer as? FilteringDiffViewer)?.hide(HiddenLines.of(result, hidden, request.oldText, request.newText, markCommentOnly, rewriteOnly), runs)
        // Follow once after a result shown when a caret move was skipped while a comparison was pending (R8); ranges stay valid during the reference pass.
        if (!revealApplied && caretFollowPending) followLastCaret(afterResult = true)
    }

    private fun documentChanged() {
        if (disposed || !isOutlineVisible) return
        cancel()
        navigator.releaseScrollSync()
        usageHighlights.clear()
        movedMarkers.clear()
        (viewer as? FilteringDiffViewer)?.forgetHidden()
        debounce.restart()
    }

    private fun cancel() {
        debounce.stop()
        running?.canceled = true
        running = null
    }

    private fun show(state: OutlineState) {
        if (state !is OutlineState.Shown) {
            shownRequest = null
            usageHighlights.clear()
            movedMarkers.clear()
        }
        this.state = state
        panel.show(state)
        strip.show(stripDetail(state))
    }

    private fun caretListener(side: CaretSide): CaretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            if (disposed || suppressCaretFollow > 0) return
            lastCaretSide = side
            lastCaretOffset = event.caret?.offset ?: return
            // A comparison is pending or running; the shown result is stale or missing until the new one (R8).
            // Focus is checked when the timer fires, so a click that moves the caret before focus settles is followed.
            if (debounce.isRunning || state !is OutlineState.Shown) caretFollowPending = true
            caretDebounce.restart()
        }
    }

    /** Fires the caret-follow debounce at once (outline/07, tests). */
    @TestOnly
    internal fun fireCaretFollow() {
        caretDebounce.stop()
        followLastCaret()
    }

    private fun isOutlineFocused(): Boolean =
        outlineFocusedOverride ?: run {
            val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
            SwingUtilities.isDescendingFrom(focus, panel)
        }

    private fun isEditorFocused(side: CaretSide): Boolean =
        editorFocusedOverride ?: run {
            val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
            val editor = if (side == CaretSide.OLD) oldEditor else newEditor
            editor != null && SwingUtilities.isDescendingFrom(focus, editor.contentComponent)
        }

    /** Selects the row of the last caret position when conditions allow (outline/07 R1-R8, R10). */
    private fun followLastCaret(afterResult: Boolean = false) {
        if (disposed || !isOutlineVisible || isMinimised || state !is OutlineState.Shown || isOutlineFocused()) return
        if (!afterResult && debounce.isRunning) {
            // A comparison is pending while the document debounce runs; the shown result is stale until the next one (R8).
            caretFollowPending = true
            return
        }
        val side = lastCaretSide ?: return
        if (!isEditorFocused(side)) return
        val offset = lastCaretOffset
        val shown = state as? OutlineState.Shown ?: return
        val match = matchingItem(shown.items, side, offset, skipContainers = true)
        if (match == null) {
            caretFollowPending = false
            return
        }
        val selected = panel.selectedItem()
        if (selected != null && selected === match) {
            caretFollowPending = false
            return
        }
        panel.reveal(OutlinePath.of(pathTo(shown.items, match)), scrollEditors = false)
        caretFollowPending = false
    }

    /** The path from the top level to [target], or a single-element path when not found in the tree. */
    private fun pathTo(items: List<OutlineItem>, target: OutlineItem): List<OutlineItem> {
        fun find(level: List<OutlineItem>, prefix: List<OutlineItem>): List<OutlineItem>? {
            for (item in level) {
                val next = prefix + item
                if (item === target) return next
                val child = find(item.children, next)
                if (child != null) return child
            }
            return null
        }
        return find(items, emptyList()) ?: listOf(target)
    }

    /** The innermost shown row for [offset] on [side], or null; the editor's Expand Declaration action uses it (outline/14 R2). */
    internal fun rowAt(side: CaretSide, offset: Int): OutlineItem? {
        val shown = state as? OutlineState.Shown ?: return null
        return matchingItem(shown.items, side, offset)
    }

    /**
     * The innermost shown row for [offset] on [side], or null (outline/07 R1-R3, R6, R7). With [skipContainers], a
     * container row matches only where [OutlineItem.followsCaretAt] allows, so a caret on an unchanged line of a container
     * keeps the selection or selects a changed member instead of framing the whole container (outline/07 R11, R12).
     */
    private fun matchingItem(items: List<OutlineItem>, side: CaretSide, offset: Int, skipContainers: Boolean = false): OutlineItem? {
        val matches = mutableListOf<Match>()
        val lines = lineOf(side)
        fun visit(level: List<OutlineItem>, index: IntArray) {
            for (item in level) {
                val i = index[0]++
                if (item.isGroup || (skipContainers && !item.followsCaretAt(side == CaretSide.OLD, offset, lines))) {
                    visit(item.children, index)
                    continue
                }
                val range = if (side == CaretSide.OLD) item.oldRange else item.newRange
                if (range != null && contains(range, offset, side)) {
                    matches += Match(item, range.end - range.start, item.kind != ItemKind.RELATIONSHIP, i)
                }
                visit(item.children, index)
            }
        }
        visit(items, intArrayOf(0))
        if (matches.isEmpty()) return null
        return matches.minWith(compareBy<Match> { it.length }.thenByDescending { it.prefer }.thenBy { it.order }).item
    }

    /** The line of an offset in the document of [side], clamped to the document. */
    private fun lineOf(side: CaretSide): (Int) -> Int {
        val document = if (side == CaretSide.OLD) oldDocument else newDocument
        return { offset -> document.getLineNumber(offset.coerceIn(0, document.textLength)) }
    }

    private data class Match(val item: OutlineItem, val length: Int, val prefer: Boolean, val order: Int)

    /** Whether [range] contains [offset] (outline/07 details: `start <= offset < end`, or `offset == end` at document end). */
    private fun contains(range: io.github.agadek.smartdiff.engine.api.TextRange, offset: Int, side: CaretSide): Boolean {
        if (offset < range.start || offset > range.end) return false
        if (offset < range.end) return true
        // offset == range.end: only the last character of the document.
        val length = when (side) {
            CaretSide.OLD -> oldDocument.textLength
            CaretSide.NEW -> newDocument.textLength
        }
        return offset == length
    }

    /**
     * What the strip says for [state]. A fallback keeps its reason and never uses the empty summary line, which would
     * claim there are no structural changes when the analysis did not run (outline/02 R9).
     */
    private fun stripDetail(state: OutlineState): String? = when (state) {
        OutlineState.Hidden -> null
        OutlineState.Loading -> "Comparing…"
        is OutlineState.NoEngine -> "No comparison engine supports ${state.languageName} files"
        OutlineState.NoMovedLines -> "No moved lines found. The native diff shows every change."
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
        caretDebounce.stop()
        cancel()
        navigator.clear()
        usageHighlights.clear()
        movedMarkers.clear()
        removeStrip()
        state = OutlineState.Hidden
        panel.show(OutlineState.Hidden)
        viewer.component.putClientProperty(CONTROLLER, null)
        oldEditor?.putUserData(EDITOR_KEY, null)
        newEditor?.putUserData(EDITOR_KEY, null)
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

        /** Debounce for caret-follow selection (outline/07 R5). */
        const val CARET_DEBOUNCE_MILLIS = 100

        /** The grey note while the reference pass runs, and after it could not run (relationships/04 R2, R17). */
        const val RESOLVING = "Resolving usages…"
        const val USAGES_NOT_GROUPED = "usages not grouped"

        /** The fallback whitespace policy before the pass reports the parsed dialect; Scala keeps its stricter policy. */
        internal fun whitespacePolicy(languageId: String): WhitespacePolicy = when (languageId) {
            "JAVA" -> WhitespacePolicy.INSIGNIFICANT
            "kotlin" -> WhitespacePolicy.KOTLIN_LINE_BREAKS
            "Shell Script" -> WhitespacePolicy.SEPARATION_SIGNIFICANT
            "Scala" -> WhitespacePolicy.SCALA3_INDENTATION
            "JSON", "JSON5" -> WhitespacePolicy.INSIGNIFICANT
            "yaml" -> WhitespacePolicy.SIGNIFICANT
            "Properties" -> WhitespacePolicy.INSIGNIFICANT
            "TOML" -> WhitespacePolicy.LINE_BREAKS_SIGNIFICANT
            "XML", "HTML", "XHTML" -> WhitespacePolicy.INSIGNIFICANT
            "Markdown" -> WhitespacePolicy.SIGNIFICANT
            "Python" -> WhitespacePolicy.INDENTATION_SIGNIFICANT
            "Rust" -> WhitespacePolicy.INSIGNIFICANT
            "JavaScript", "ECMAScript 6", "JSX Harmony", "Flow JS", "TypeScript", "TypeScript JSX" -> WhitespacePolicy.INSIGNIFICANT
            "go" -> WhitespacePolicy.LINE_BREAKS_SIGNIFICANT
            "CSS", "SCSS", "LESS" -> WhitespacePolicy.INSIGNIFICANT
            "PHP" -> WhitespacePolicy.INSIGNIFICANT
            else -> WhitespacePolicy.SIGNIFICANT
        }

        /** The outline toggle's state for one diff window, shared by the viewers it shows one after another. */
        private const val CONTROLLER = "smartdiff.outline.controller"

        /** The outline controller attached to a diff editor, for its Expand Declaration action (outline/14 R2). */
        val EDITOR_KEY = Key.create<OutlineController>("smartdiff.outline.controller.editor")

        /** The outline attached to [viewer], or null if it has none. */
        fun of(viewer: FrameDiffTool.DiffViewer?): OutlineController? =
            viewer?.component?.getClientProperty(CONTROLLER) as? OutlineController
    }
}
