package io.github.agadek.smartdiff.ui

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion

/**
 * The diff's collapsed unchanged fragments (outline/14): the fold regions the platform's `FoldingModelSupport.addFolding`
 * creates for each unchanged block when "Collapse unchanged fragments" is on. Such a region covers whole lines, from the
 * start of the first line to the end of the last, and shows a blank placeholder. A manual code fold the user made with the
 * editor's folding actions usually has a non-blank placeholder such as `{...}` and is left alone.
 *
 * Only the public folding surface is read here; no region is added, removed or split, and the diff's settings are not
 * touched (R10).
 */
internal object UnchangedFragments {

    /** The editor's fold regions that are valid, collapsed, cover whole lines and have a blank placeholder. */
    fun collapsed(editor: Editor): List<FoldRegion> {
        val document = editor.document
        return editor.foldingModel.allFoldRegions.filter { region ->
            region.isValid && !region.isExpanded && region.placeholderText.isBlank() && coversWholeLines(document, region)
        }
    }

    /** Whether [region] starts at a line start and ends at a line end, as `FoldingModelSupport.addFolding` creates. */
    private fun coversWholeLines(document: Document, region: FoldRegion): Boolean {
        val start = region.startOffset
        val end = region.endOffset
        if (start < 0 || end < start || end > document.textLength) return false
        if (document.getLineStartOffset(document.getLineNumber(start)) != start) return false
        return document.getLineEndOffset(document.getLineNumber(end)) == end
    }
}
