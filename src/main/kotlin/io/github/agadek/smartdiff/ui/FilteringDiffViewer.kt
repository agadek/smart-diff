package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleDiffChange
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.util.base.HighlightPolicy
import com.intellij.diff.util.Side
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * The "Side-by-side viewer (SMART Diff)" in the diff's viewer list (technical specification §10): IntelliJ's own
 * side-by-side viewer, except that a change lying entirely on lines of changes the outline filter hides is shown as
 * excluded, like a change the IDE ignores, and next/previous change skips it. It is offered beside the native viewer
 * rather than in its place; the IDE remembers the viewer chosen for each kind of diff. It has its own icon, the
 * side-by-side mark with a filter funnel (outline/04 R10).
 */
class FilteringDiffTool : FrameDiffTool {

    override fun getName(): String = NAME

    /**
     * The viewer switcher's icon (outline/04 R10). The platform's public `FrameDiffTool.getIcon()` exists from 2026.2;
     * this plugin compiles against 2025.1, so the method is declared as a property without `override`. On a platform
     * that has it, the property's getter overrides the interface default at runtime and the switcher uses this icon.
     */
    val icon: Icon
        get() = IconLoader.getIcon(ICON_PATH, FilteringDiffTool::class.java)

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean = SimpleDiffViewer.canShowRequest(context, request)

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer = FilteringDiffViewer(context, request)

    companion object {
        const val NAME = "Side-by-side viewer (SMART Diff)"
        internal const val ICON_PATH = "/icons/smartDiffViewer.svg"
    }
}

class FilteringDiffViewer(context: DiffContext, request: DiffRequest) : SimpleDiffViewer(context, request) {

    /** Read by the comparison on a pooled thread; replaced on the event dispatch thread. */
    @Volatile
    var hiddenLines: HiddenLines = HiddenLines.NONE
        private set

    /**
     * The changed runs the viewer highlights in place of the native word highlights (declarations/10 R11). Read by the
     * comparison on a pooled thread; replaced on the event dispatch thread.
     */
    @Volatile
    var changedRuns: ChangedRuns = ChangedRuns.NONE
        private set

    /**
     * Fades the changes on [lines] and highlights [runs] from now on, comparing the sides again if that changes
     * anything. Every caller that clears the hidden lines clears the runs too (declarations/10 R16).
     */
    fun hide(lines: HiddenLines, runs: ChangedRuns = ChangedRuns.NONE) {
        if (lines == hiddenLines && runs == changedRuns) return
        hiddenLines = lines
        changedRuns = runs
        rediff()
    }

    /**
     * Forgets the hidden lines and the changed runs without comparing again, for an edit that makes them stale; the
     * edit's own rediff shows every change with the native word highlights (declarations/10 R16).
     */
    fun forgetHidden() {
        hiddenLines = HiddenLines.NONE
        changedRuns = ChangedRuns.NONE
    }

    override fun apply(changes: List<SimpleDiffChange>?, isContentsEqual: Boolean): Runnable {
        val lines = hiddenLines
        val runs = changedRuns.takeIf { highlightsByWord() } ?: ChangedRuns.NONE
        if (changes == null || (lines.isEmpty && runs.isEmpty)) return super.apply(changes, isContentsEqual)
        val filtered = changes.map { change ->
            val hidden = lines.covers(
                change.getStartLine(Side.LEFT), change.getEndLine(Side.LEFT),
                change.getStartLine(Side.RIGHT), change.getEndLine(Side.RIGHT),
            )
            // declarations/10 R11 to R14: the same lines and offsets, so index, next and previous change, the gutter
            // actions and fading are as before; only the word highlights come from the runs. A fragment the runs cannot
            // map, for example after the document changed, keeps the native one.
            val fragment = runs.fragment(change.fragment) ?: change.fragment
            val skipped = change.isSkipped || hidden
            if (fragment === change.fragment && skipped == change.isSkipped) change
            else SimpleDiffChange(change.index, fragment, change.isExcluded, skipped)
        }
        return super.apply(filtered, isContentsEqual)
    }

    /**
     * Whether the viewer highlights words or characters; any other policy is left as the native viewer shows it
     * (declarations/10 R15). Splitting by words splits a change by its inner fragments, so replacing them would leave
     * the split and the highlights out of step.
     */
    private fun highlightsByWord(): Boolean {
        val policy = textSettings.highlightPolicy
        return policy == HighlightPolicy.BY_WORD || policy == HighlightPolicy.BY_CHAR
    }
}
