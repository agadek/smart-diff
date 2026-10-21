package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleDiffChange
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.util.Side

/**
 * The "Side-by-side viewer (SMART Diff)" in the diff's viewer list (technical specification §10): IntelliJ's own
 * side-by-side viewer, except that a change lying entirely on lines of changes the outline filter hides is shown as
 * excluded, like a change the IDE ignores, and next/previous change skips it. It is offered beside the native viewer
 * rather than in its place; the IDE remembers the viewer chosen for each kind of diff.
 */
class FilteringDiffTool : FrameDiffTool {

    override fun getName(): String = NAME

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean = SimpleDiffViewer.canShowRequest(context, request)

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer = FilteringDiffViewer(context, request)

    companion object {
        const val NAME = "Side-by-side viewer (SMART Diff)"
    }
}

class FilteringDiffViewer(context: DiffContext, request: DiffRequest) : SimpleDiffViewer(context, request) {

    /** Read by the comparison on a pooled thread; replaced on the event dispatch thread. */
    @Volatile
    var hiddenLines: HiddenLines = HiddenLines.NONE
        private set

    /** Fades the changes on [lines] from now on, comparing the sides again if that changes anything. */
    fun hide(lines: HiddenLines) {
        if (lines == hiddenLines) return
        hiddenLines = lines
        rediff()
    }

    /** Forgets the hidden lines without comparing again, for an edit that makes them stale; the edit's own rediff shows every change. */
    fun forgetHidden() {
        hiddenLines = HiddenLines.NONE
    }

    override fun apply(changes: List<SimpleDiffChange>?, isContentsEqual: Boolean): Runnable {
        val lines = hiddenLines
        if (changes == null || lines.isEmpty) return super.apply(changes, isContentsEqual)
        val filtered = changes.map { change ->
            val hidden = lines.covers(
                change.getStartLine(Side.LEFT), change.getEndLine(Side.LEFT),
                change.getStartLine(Side.RIGHT), change.getEndLine(Side.RIGHT),
            )
            if (hidden && !change.isSkipped) SimpleDiffChange(change.index, change.fragment, change.isExcluded, true) else change
        }
        return super.apply(filtered, isContentsEqual)
    }
}
