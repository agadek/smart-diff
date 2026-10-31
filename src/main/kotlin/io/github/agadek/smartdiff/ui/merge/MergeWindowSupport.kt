package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.DiffEditorTitleCustomizer
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.merge.TextMergeRequest
import com.intellij.diff.tools.util.text.LineOffsetsUtil
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.MergeConflictType
import com.intellij.diff.util.MergeRangeUtil
import com.intellij.diff.util.ThreeSide
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import io.github.agadek.smartdiff.ui.languageId
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel

/** What the merge window gives the outline: its language, its side names and its conflict ranges. */
internal object MergeWindowSupport {

    /** The language shared by the three merge contents, or null when they are not documents of one supported language. */
    fun languageId(project: Project?, contents: List<DiffContent>): String? {
        if (contents.size != 3) return null
        val languages = contents.map { (it as? DocumentContent)?.languageId() ?: return null }.toSet()
        val language = languages.singleOrNull() ?: return null
        val registry = EngineRegistry.getInstance(project ?: ProjectManager.getInstance().defaultProject)
        return language.takeIf { registry.select(it).selected != null }
    }

    /**
     * The names of the two sides (merge window spec R5, R6): the text of the pane's title component when the request
     * has one, else the content title the caller gave. The existing side-name rules shorten either to its branch or
     * revision.
     */
    fun names(request: TextMergeRequest): Pair<MergeSideName, MergeSideName> {
        val titles = request.contentTitles
        val customizers = request.getUserData(DiffUserDataKeysEx.EDITORS_TITLE_CUSTOMIZER)
        // A merge request has three pane titles; a two-entry customizer list names only the two sides.
        val rightCustomizer = if (customizers?.size == 2) 1 else 2
        return MergeSideNames.fromTitle(paneTitle(customizers, titles, 0, 0), "Left") to
            MergeSideNames.fromTitle(paneTitle(customizers, titles, 2, rightCustomizer), "Right")
    }

    /**
     * The base ranges of the merge conflicts, or null when they cannot be read. The merge viewer computes them the same
     * way from the three texts through public API (spike S6).
     */
    fun conflictRanges(left: CharSequence, base: CharSequence, right: CharSequence): List<TextRange>? = try {
        val texts = listOf(left, base, right)
        val offsets = texts.map(LineOffsetsUtil::create)
        val baseOffsets = offsets[ThreeSide.BASE.index]
        // The window may use another policy, such as ignoring whitespace. Default policy can only add conflicts, so a
        // row is marked "merged without conflict" only when this stricter reading agrees (ADR 0003).
        ComparisonManager.getInstance()
            .mergeLines(left, base, right, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
            .filter {
                MergeRangeUtil.getLineMergeType(it, texts, offsets, ComparisonPolicy.DEFAULT).type == MergeConflictType.Type.CONFLICT
            }
            .map {
                val startLine = it.getStartLine(ThreeSide.BASE).coerceIn(0, baseOffsets.lineCount)
                val endLine = it.getEndLine(ThreeSide.BASE).coerceIn(startLine, baseOffsets.lineCount)
                val start = if (startLine == baseOffsets.lineCount) baseOffsets.textLength else baseOffsets.getLineStart(startLine)
                val end = if (endLine > startLine) {
                    baseOffsets.getLineEnd((endLine - 1).coerceAtMost(baseOffsets.lineCount - 1))
                } else {
                    start
                }
                TextRange(start, end)
            }
    } catch (e: DiffTooBigException) {
        null
    } catch (e: RuntimeException) {
        // The conflicts are unreadable; the rows stay plain and the note is left out (merge window spec R14).
        null
    }

    /** A pane's title component text, or the content title when the request has no usable component for that pane. */
    private fun paneTitle(
        customizers: List<DiffEditorTitleCustomizer>?,
        titles: List<String?>,
        titleIndex: Int,
        customizerIndex: Int,
    ): String? {
        val text = customizers?.getOrNull(customizerIndex)?.getLabel()?.let(::componentText)?.takeIf { it.isNotBlank() }
        return text ?: titles.getOrNull(titleIndex)
    }

    private fun componentText(component: Component): String? = when (component) {
        is JLabel -> component.text
        is AbstractButton -> component.text
        is Container -> component.components.firstNotNullOfOrNull { componentText(it) }
        else -> null
    }
}
