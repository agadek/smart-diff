package io.github.agadek.smartdiff.engine.lines

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.DiffFragment
import com.intellij.diff.fragments.LineFragment
import com.intellij.openapi.progress.util.AbstractProgressIndicatorBase
import io.github.agadek.smartdiff.engine.api.AnalysisNote
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.LineEngineProvider
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import java.util.PriorityQueue
import kotlin.math.abs

/**
 * Compares a file no comparison engine supports by its lines and reports unique runs of lines the native diff shows as
 * removed on one side and added on the other as moved blocks (engines/04). It is not registered on the `comparisonEngine`
 * extension point: the UI decides when to use it through [LineEngineProvider].
 */
class LineEngine : ComparisonEngine, LineEngineProvider {
    override val id: EngineId = ID
    override val displayName: String = "Lines"
    override val version: String = VERSION

    override fun supports(languageId: String): Boolean = true

    override fun engine(): ComparisonEngine = this

    override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
        cancellation.checkCanceled()
        val oldLines = lines(request.oldText)
        val newLines = lines(request.newText)
        // The indicator is only a cancellation bridge: the platform's line diff defines the hunks the user sees (R5).
        val indicator = object : AbstractProgressIndicatorBase() {
            override fun checkCanceled() = cancellation.checkCanceled()
        }
        val fragments = ComparisonManager.getInstance().compareLines(
            request.oldText, request.newText, ComparisonPolicy.DEFAULT, indicator,
        )

        val removed = BooleanArray(oldLines.size)
        val added = BooleanArray(newLines.size)
        for (fragment in fragments) {
            cancellation.checkCanceled()
            for (i in fragment.startLine1.coerceAtLeast(0) until minOf(fragment.endLine1, oldLines.size)) removed[i] = true
            for (j in fragment.startLine2.coerceAtLeast(0) until minOf(fragment.endLine2, newLines.size)) added[j] = true
        }

        val oldPosition = positions(removed)
        val newPosition = positions(added)
        val oldKeys = removed.indices.filter { removed[it] }.map { oldLines[it].key }
        val newKeys = added.indices.filter { added[it] }.map { newLines[it].key }
        val candidates = candidates(oldLines, newLines, removed, added, cancellation)
            .filter { qualifies(it, oldLines, newLines) && unique(it, oldKeys, newKeys, oldPosition, newPosition) }
        val blocks = select(candidates, oldLines, newLines, oldKeys, newKeys, oldPosition, newPosition, cancellation)

        val moved = blocks.sortedBy { it.newStart }.map { block ->
            val oldStart = oldLines[block.oldStart]
            val oldEnd = oldLines[block.oldStart + block.length - 1]
            val newStart = newLines[block.newStart]
            val newEnd = newLines[block.newStart + block.length - 1]
            val details = (0 until block.length).mapNotNull { k ->
                val old = oldLines[block.oldStart + k]
                val new = newLines[block.newStart + k]
                if (old.text == new.text) null
                else RangePair(TextRange(old.start, old.end), TextRange(new.start, new.end))
            }
            ChangeNode(
                subject = Subject.Region("lines ${block.oldStart + 1}–${block.oldStart + block.length} → ${block.newStart + 1}–${block.newStart + block.length}"),
                oldRange = TextRange(oldStart.start, oldEnd.end),
                newRange = TextRange(newStart.start, newEnd.end),
                presence = Presence.MATCHED,
                moved = true,
                content = if (details.isEmpty()) Content.UNCHANGED else Content.CHANGED,
                details = details,
            )
        }

        val movedOld = blocks.flatMapTo(HashSet()) { (it.oldStart until it.oldStart + it.length).toSet() }
        val movedNew = blocks.flatMapTo(HashSet()) { (it.newStart until it.newStart + it.length).toSet() }
        val oldRest = removed.indices.filter { removed[it] && it !in movedOld }
        val newRest = added.indices.filter { added[it] && it !in movedNew }
        val regionDetails = oldRest.map { RangePair(TextRange(oldLines[it].start, oldLines[it].end), null) } +
            newRest.map { RangePair(null, TextRange(newLines[it].start, newLines[it].end)) } +
            fragments.flatMap { innerRanges(it, oldLines.size, newLines.size) }
        val fileRegion = if (oldRest.isEmpty() && newRest.isEmpty()) null else ChangeNode(
            subject = Subject.Region(FILE_REGION),
            oldRange = if (oldRest.isEmpty()) TextRange(0, 0) else TextRange(oldLines[oldRest.first()].start, oldLines[oldRest.last()].end),
            newRange = if (newRest.isEmpty()) TextRange(0, 0) else TextRange(newLines[newRest.first()].start, newLines[newRest.last()].end),
            presence = Presence.MATCHED,
            content = Content.CHANGED,
            details = regionDetails,
        )
        cancellation.checkCanceled()
        return ComparisonResult(
            id, version, Status.ANALYZED,
            fileRegion = fileRegion, changes = moved, analysisNote = AnalysisNote.LINES,
        )
    }

    /** A line's text includes its terminator; its key is that text without leading or trailing whitespace (R6). */
    private class Line(val text: String, val key: String, val start: Int, val end: Int)

    private fun lines(text: CharSequence): List<Line> {
        val result = mutableListOf<Line>()
        var start = 0
        while (start < text.length) {
            var end = start
            while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
            if (end < text.length) {
                if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') end++
                end++
            }
            val body = text.subSequence(start, end).toString()
            result += Line(body, body.trim(), start, end)
            start = end
        }
        return result
    }

    /** The index of each removed or added line in the list of all removed or added lines, -1 for unchanged lines. */
    private fun positions(flags: BooleanArray): IntArray {
        val result = IntArray(flags.size) { -1 }
        var next = 0
        for (i in flags.indices) if (flags[i]) result[i] = next++
        return result
    }

    /** Maximal runs of a removed line and an added line with the same key extended while the keys keep matching. */
    private fun candidates(
        old: List<Line>, new: List<Line>, removed: BooleanArray, added: BooleanArray, cancellation: Cancellation,
    ): List<Candidate> {
        val addedByKey = HashMap<String, MutableList<Int>>()
        for (j in added.indices) if (added[j]) addedByKey.getOrPut(new[j].key) { mutableListOf() } += j
        val result = mutableListOf<Candidate>()
        for (i in removed.indices) {
            if (!removed[i]) continue
            cancellation.checkCanceled()
            for (j in addedByKey[old[i].key].orEmpty()) {
                // Only maximal runs: a run with a matching pair before it is found from that earlier pair.
                if (i > 0 && j > 0 && removed[i - 1] && added[j - 1] && old[i - 1].key == new[j - 1].key) continue
                var length = 1
                while (i + length < old.size && j + length < new.size &&
                    removed[i + length] && added[j + length] && old[i + length].key == new[j + length].key
                ) length++
                result += Candidate(i, j, length)
            }
        }
        return result
    }

    private fun qualifies(candidate: Candidate, old: List<Line>, new: List<Line>): Boolean =
        candidate.length >= MIN_BLOCK_LINES &&
            (0 until candidate.length).sumOf { k -> old[candidate.oldStart + k].key.count { !it.isWhitespace() } } >=
                MIN_NON_WHITESPACE_CHARS

    /** The conservative rule of ADR 0003: the key run occurs exactly once on each side (R7). */
    private fun unique(
        candidate: Candidate, oldKeys: List<String>, newKeys: List<String>, oldPosition: IntArray, newPosition: IntArray,
    ): Boolean =
        occursOnce(oldKeys, oldPosition[candidate.oldStart], candidate.length) &&
            occursOnce(newKeys, newPosition[candidate.newStart], candidate.length)

    private fun occursOnce(keys: List<String>, start: Int, length: Int): Boolean {
        var found = 0
        for (begin in 0..keys.size - length) {
            var same = true
            for (k in 0 until length) {
                if (keys[begin + k] != keys[start + k]) {
                    same = false
                    break
                }
            }
            if (same && ++found > 1) return false
        }
        return found == 1
    }

    /** Longest first, then the nearest pair of start lines, then the earliest new line (R8). */
    private fun select(
        candidates: List<Candidate>, old: List<Line>, new: List<Line>,
        oldKeys: List<String>, newKeys: List<String>, oldPosition: IntArray, newPosition: IntArray,
        cancellation: Cancellation,
    ): List<Candidate> {
        val queue = PriorityQueue<Candidate>(
            compareByDescending<Candidate> { it.length }
                .thenBy { abs(it.oldStart - it.newStart) }
                .thenBy { it.newStart },
        )
        queue.addAll(candidates)
        val usedOld = BooleanArray(old.size)
        val usedNew = BooleanArray(new.size)
        val taken = mutableListOf<Candidate>()
        while (queue.isNotEmpty()) {
            cancellation.checkCanceled()
            val candidate = queue.poll()
            if ((candidate.oldStart until candidate.oldStart + candidate.length).all { !usedOld[it] } &&
                (candidate.newStart until candidate.newStart + candidate.length).all { !usedNew[it] }
            ) {
                taken += candidate
                for (i in candidate.oldStart until candidate.oldStart + candidate.length) usedOld[i] = true
                for (j in candidate.newStart until candidate.newStart + candidate.length) usedNew[j] = true
                continue
            }
            // An overlapping run may still contribute a shorter part that shares no line with a taken block (R8).
            val trimmed = longestUnusedPart(candidate, usedOld, usedNew) ?: continue
            if (trimmed.length == candidate.length) continue
            if (qualifies(trimmed, old, new) && unique(trimmed, oldKeys, newKeys, oldPosition, newPosition)) {
                queue.add(trimmed)
            }
        }
        return taken
    }

    private fun longestUnusedPart(candidate: Candidate, usedOld: BooleanArray, usedNew: BooleanArray): Candidate? {
        var bestStart = -1
        var bestLength = 0
        var runStart = -1
        for (k in 0 until candidate.length) {
            val free = !usedOld[candidate.oldStart + k] && !usedNew[candidate.newStart + k]
            if (free && runStart < 0) runStart = k
            if ((!free || k == candidate.length - 1) && runStart >= 0) {
                val end = if (free) k + 1 else k
                if (end - runStart > bestLength) {
                    bestStart = runStart
                    bestLength = end - runStart
                }
                runStart = -1
            }
        }
        if (bestStart < 0 || bestLength < MIN_BLOCK_LINES) return null
        return Candidate(candidate.oldStart + bestStart, candidate.newStart + bestStart, bestLength)
    }

    /** Fine-grained ranges the platform's line diff reports inside a fragment, added to the file region's details. */
    private fun innerRanges(fragment: LineFragment, oldSize: Int, newSize: Int): List<RangePair> =
        (fragment.innerFragments ?: emptyList()).mapNotNull { inner: DiffFragment ->
            val old = range(inner.startOffset1, inner.endOffset1, oldSize)
            val new = range(inner.startOffset2, inner.endOffset2, newSize)
            if (old == null && new == null) null else RangePair(old, new)
        }

    private fun range(start: Int, end: Int, size: Int): TextRange? =
        if (start in 0..end && end <= size) TextRange(start, end) else null

    private data class Candidate(val oldStart: Int, val newStart: Int, val length: Int)

    companion object {
        const val ID_VALUE = "smartdiff.lines"
        val ID = EngineId(ID_VALUE)
        const val VERSION = "1"

        /** Three lines and 30 non-whitespace characters keep short repeated lines from forming blocks (R5). */
        const val MIN_BLOCK_LINES = 3
        const val MIN_NON_WHITESPACE_CHARS = 30

        private const val FILE_REGION = "File region"
    }
}
