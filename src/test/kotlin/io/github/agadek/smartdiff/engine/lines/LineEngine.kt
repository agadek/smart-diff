package io.github.agadek.smartdiff.engine.lines

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * Test-only engine that reports each run of changed lines as a [Subject.Region] (technical specification §12,
 * abstraction test). It supports every language and knows nothing about declarations.
 */
class LineEngine : ComparisonEngine {
    override val id = ID
    override val displayName = "Lines (test)"
    override val version = "1"

    override fun supports(languageId: String): Boolean = true

    override fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
        val old = lines(request.oldText)
        val new = lines(request.newText)
        val changes = hunks(old.map { it.text }, new.map { it.text }, cancellation).map { (o, n) ->
            val oldRange = o.takeUnless { it.isEmpty() }?.let { TextRange(old[it.first].start, old[it.last].end) }
            val newRange = n.takeUnless { it.isEmpty() }?.let { TextRange(new[it.first].start, new[it.last].end) }
            ChangeNode(
                subject = Subject.Region(describe(o, n)),
                oldRange = oldRange,
                newRange = newRange,
                presence = when {
                    oldRange == null -> Presence.ADDED
                    newRange == null -> Presence.REMOVED
                    else -> Presence.MATCHED
                },
                content = if (oldRange != null && newRange != null) Content.CHANGED else Content.UNCHANGED,
            )
        }
        return ComparisonResult(id, version, Status.ANALYZED, changes = changes)
    }

    private class Line(val text: String, val start: Int, val end: Int)

    /** Lines with their terminators, so the last line differs from the same line followed by a line break. */
    private fun lines(text: CharSequence): List<Line> {
        val lines = mutableListOf<Line>()
        var start = 0
        while (start < text.length) {
            val lineBreak = text.indexOf('\n', start)
            val end = if (lineBreak < 0) text.length else lineBreak + 1
            lines += Line(text.subSequence(start, end).toString(), start, end)
            start = end
        }
        return lines
    }

    /** Old and new line index ranges of each run of lines outside a longest common subsequence. */
    private fun hunks(old: List<String>, new: List<String>, cancellation: Cancellation): List<Pair<IntRange, IntRange>> {
        // lcs[i][j]: length of the LCS of old[i:] and new[j:].
        val lcs = Array(old.size + 1) { IntArray(new.size + 1) }
        for (i in old.indices.reversed()) {
            cancellation.checkCanceled()
            for (j in new.indices.reversed()) {
                lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val hunks = mutableListOf<Pair<IntRange, IntRange>>()
        var i = 0
        var j = 0
        while (i < old.size || j < new.size) {
            if (i < old.size && j < new.size && old[i] == new[j]) {
                i++; j++
                continue
            }
            val oldStart = i
            val newStart = j
            while ((i < old.size || j < new.size) && !(i < old.size && j < new.size && old[i] == new[j])) {
                if (j >= new.size || (i < old.size && lcs[i + 1][j] >= lcs[i][j + 1])) i++ else j++
            }
            hunks += (oldStart until i) to (newStart until j)
        }
        return hunks
    }

    private fun describe(old: IntRange, new: IntRange): String = when {
        old.isEmpty() -> "lines ${span(new)} added"
        new.isEmpty() -> "lines ${span(old)} removed"
        else -> "lines ${span(old)} changed to ${span(new)}"
    }

    private fun span(lines: IntRange) = if (lines.first == lines.last) "${lines.first + 1}" else "${lines.first + 1}-${lines.last + 1}"

    companion object {
        val ID = EngineId("smartdiff.test.lines")
    }
}
