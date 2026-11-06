package io.github.agadek.smartdiff.ui

import com.intellij.diff.tools.util.SyncScrollSupport
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.markup.RangeHighlighter
import io.github.agadek.smartdiff.engine.api.TextRange
import kotlin.math.roundToInt

/** A vertical span of an editor in document pixels, from the top of its first framed line to the bottom of its last. */
internal data class ScrollFrame(val start: Double, val end: Double) {
    init {
        require(end >= start) { "a frame ends before it starts" }
    }

    /** Whether any part of the frame lies between [visibleStart] and [visibleEnd]. */
    fun intersects(visibleStart: Double, visibleEnd: Double): Boolean =
        start < visibleEnd && end > visibleStart
}

/** The frame of [range] in [editor], in document pixels. */
internal fun scrollFrame(editor: Editor, range: TextRange): ScrollFrame {
    val start = range.start.coerceIn(0, editor.document.textLength)
    val end = range.end.coerceIn(start, editor.document.textLength)
    val firstLine = editor.document.getLineNumber(start)
    val lastLine = editor.document.getLineNumber(if (end > start) end - 1 else start)
    val top = editor.logicalPositionToXY(LogicalPosition(firstLine, 0)).y.toDouble()
    val bottom = editor.logicalPositionToXY(LogicalPosition(lastLine, 0)).y + editor.lineHeight.toDouble()
    return ScrollFrame(top, bottom)
}

/**
 * Maps the top of the visible area from one editor to the other so a selected pair keeps the same position relative to
 * its frames: by the same offset before the frames, by the same fraction of their heights inside them, and by the same
 * offset after them. Inside the frames the scale differs when the two versions have different lengths.
 */
internal class FrameScrollMapping(private val master: ScrollFrame, private val slave: ScrollFrame) {

    fun map(masterOffset: Double): Double = when {
        masterOffset < master.start -> slave.start - (master.start - masterOffset)
        masterOffset > master.end -> slave.end + (masterOffset - master.end)
        else -> {
            val masterHeight = master.end - master.start
            if (masterHeight <= 0.0) slave.start
            else slave.start + (masterOffset - master.start) * (slave.end - slave.start) / masterHeight
        }
    }
}

/** Pauses and resumes the platform's synchronized scrolling. */
internal interface ScrollPause {
    fun enter()

    fun exit()
}

/** Uses the platform's public synchronized-scrolling pause counter. */
internal fun SyncScrollSupport.Support.asScrollPause(): ScrollPause {
    val support = this
    return object : ScrollPause {
        override fun enter() = support.enterDisableScrollSection()

        override fun exit() = support.exitDisableScrollSection()
    }
}

/**
 * Keeps the frames of a selected outline item aligned while the user scrolls, with the platform's synchronized
 * scrolling paused. It stops itself and releases the pause when a frame leaves the visible area; the owner stops it
 * when the selection changes or clears, a document changes, or the viewer is disposed.
 */
internal class FrameScrollSync(
    private val editors: List<Editor>,
    private val frameHighlighters: List<RangeHighlighter>,
    private val pause: ScrollPause?,
) {
    init {
        require(editors.size == frameHighlighters.size) { "every editor needs a frame" }
        require(editors.size >= 2) { "keeping frames aligned needs at least two editors" }
    }

    private val listener = VisibleAreaListener { changed(it) }
    private var following = false
    private var adjusting = false

    /** Whether the frames are being kept aligned, for tests. */
    internal val isFollowing: Boolean get() = following

    /**
     * Starts following when every frame is visible. The caller scrolls the editors to the selected item first; when a
     * frame is not in view there is no pair to hold, so the platform's scrolling stays as it is.
     */
    fun start() {
        stop()
        val frames = currentFrames() ?: return
        if (editors.indices.any { !frames[it].intersects(editors[it]) }) return
        pause?.enter()
        following = true
        editors.forEach { it.scrollingModel.addVisibleAreaListener(listener) }
    }

    /** Releases the pause and stops following; safe to call more than once. */
    fun stop() {
        if (!following) return
        following = false
        editors.forEach { it.scrollingModel.removeVisibleAreaListener(listener) }
        pause?.exit()
    }

    private fun changed(event: VisibleAreaEvent) {
        if (!following || adjusting) return
        val frames = currentFrames()
        if (frames == null) {
            stop()
            return
        }
        val master = editors.indexOf(event.editor)
        if (master < 0) return
        if (editors.indices.any { !frames[it].intersects(editors[it]) }) {
            stop()
            return
        }
        adjusting = true
        try {
            val masterTop = event.newRectangle.y.toDouble()
            val masterLeft = event.newRectangle.x.toDouble()
            for (index in editors.indices) {
                if (index == master) continue
                val editor = editors[index]
                val targetTop = FrameScrollMapping(frames[master], frames[index]).map(masterTop).roundToInt()
                editor.scrollingModel.disableAnimation()
                try {
                    if (editor.scrollingModel.visibleArea.y != targetTop) editor.scrollingModel.scrollVertically(targetTop)
                    if (editor.scrollingModel.visibleArea.x != masterLeft.roundToInt()) {
                        editor.scrollingModel.scrollHorizontally(masterLeft.roundToInt())
                    }
                } finally {
                    editor.scrollingModel.enableAnimation()
                }
            }
        } finally {
            adjusting = false
        }
    }

    /**
     * The frames at the current line heights, folding and inlays. Highlighters keep the document offsets through those
     * changes, so their pixel positions are read again on every scroll instead of being kept from the selection.
     */
    private fun currentFrames(): List<ScrollFrame>? {
        if (frameHighlighters.any { !it.isValid }) return null
        return frameHighlighters.mapIndexed { index, highlighter ->
            scrollFrame(editors[index], TextRange(highlighter.startOffset, highlighter.endOffset))
        }
    }

    private fun ScrollFrame.intersects(editor: Editor): Boolean {
        val area = editor.scrollingModel.visibleArea
        return intersects(area.y.toDouble(), (area.y + area.height).toDouble())
    }
}
