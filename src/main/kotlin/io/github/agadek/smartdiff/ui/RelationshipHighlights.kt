package io.github.agadek.smartdiff.ui

import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * The usages of each shown rename in the editors, in that rename's palette colour (relationships/06 R12). The marks
 * are exact-range highlights under the selection layer, so they add a background without replacing the diff's own
 * colours or the navigator's frame and usage highlights.
 */
internal class RelationshipHighlights(private val viewer: TwosideTextDiffViewer) {

    private val highlighters = mutableListOf<RangeHighlighter>()

    /** Replaces the highlights with those of [result]'s shown renames; a hidden kind adds none. */
    fun show(result: ComparisonResult, hiddenKinds: Set<RelationshipKind>) {
        clear()
        for (usage in RefactoringsModel.usageHighlights(result, hiddenKinds)) {
            val attributes = TextAttributes().apply { backgroundColor = RelationshipPalette.colour(usage.colour) }
            add(viewer.editor1, usage.oldRange, attributes)
            add(viewer.editor2, usage.newRange, attributes)
        }
    }

    fun clear() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
    }

    /** The highlighters in place, for tests. */
    internal val shown: List<RangeHighlighter> get() = highlighters.toList()

    private fun add(editor: Editor, range: TextRange, attributes: TextAttributes) {
        val start = range.start.coerceIn(0, editor.document.textLength)
        val end = range.end.coerceIn(start, editor.document.textLength)
        if (start == end) return
        val highlighter = editor.markupModel.addRangeHighlighter(
            start,
            end,
            HighlighterLayer.SELECTION - 2,
            attributes,
            HighlighterTargetArea.EXACT_RANGE,
        )
        highlighters += highlighter
    }
}
