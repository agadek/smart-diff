package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import io.github.agadek.smartdiff.ui.insertOutlineSplitter
import io.github.agadek.smartdiff.ui.languageId
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The placeholder of spike S6 for the three-way merge outline (docs/THREE-WAY-MERGE.md, phase 0, ADR 0007): it shows where the outline will sit in three-sided diffs and in the merge window, and which side
 * titles it receives. It is off unless the registry key [REGISTRY_KEY] is on, and is replaced by the outline in later
 * phases.
 */
internal object MergeOutlinePreview {

    const val REGISTRY_KEY = "smartdiff.merge.outline.preview"

    private const val PROPORTION_KEY = "smartdiff.merge.outline.proportion"

    fun isEnabled(): Boolean = Registry.`is`(REGISTRY_KEY, false)

    /** Attaches the placeholder to a three-sided diff (plan §6.1); the middle content is the base. */
    fun attachToThreeSidedDiff(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        if (!isEnabled()) return
        val contentRequest = request as? ContentDiffRequest ?: return
        val languageId = supportedLanguage(context.project, contentRequest.contents) ?: return
        attach(viewer.component, MergeOutlinePlaceholder.Kind.THREE_SIDED_DIFF, languageId, contentRequest.contentTitles)
    }

    /**
     * The language shared by three document [contents] (left, base, right), or null when they are not three documents
     * of one language with an engine.
     */
    fun supportedLanguage(project: Project?, contents: List<DiffContent>): String? {
        if (contents.size != 3) return null
        val languages = contents.map { (it as? DocumentContent)?.languageId() ?: return null }.toSet()
        val languageId = languages.singleOrNull() ?: return null
        val registry = EngineRegistry.getInstance(project ?: ProjectManager.getInstance().defaultProject)
        return languageId.takeIf { registry.select(it).selected != null }
    }

    /** Puts the placeholder beside the editors of a viewer component; returns it, or null if the layout is not known. */
    fun attach(component: JComponent, kind: MergeOutlinePlaceholder.Kind, languageId: String, titles: List<String?>): MergeOutlinePlaceholder? {
        val splitter = insertOutlineSplitter(component, PROPORTION_KEY) ?: return null
        val placeholder = MergeOutlinePlaceholder(kind, languageId, titles.getOrNull(0), titles.getOrNull(2))
        splitter.secondComponent = placeholder
        return placeholder
    }
}

/** What the outline will show in [kind]: the language and both sides' titles as the window gives them, without markup. */
internal class MergeOutlinePlaceholder(val kind: Kind, val languageId: String, leftTitle: String?, rightTitle: String?) : JPanel(BorderLayout()) {

    enum class Kind(val text: String) { THREE_SIDED_DIFF("three-sided diff"), MERGE_WINDOW("merge window") }

    val leftTitle: String = plain(leftTitle)
    val rightTitle: String = plain(rightTitle)

    init {
        val label = JBLabel(
            "<html>SMART Diff merge outline preview<br>${kind.text}, $languageId<br><br>" +
                "Left: ${StringUtil.escapeXmlEntities(this.leftTitle)}<br>Right: ${StringUtil.escapeXmlEntities(this.rightTitle)}</html>",
        )
        label.verticalAlignment = SwingConstants.TOP
        label.border = JBUI.Borders.empty(8)
        add(label, BorderLayout.CENTER)
    }

    private companion object {
        fun plain(title: String?): String = title?.let { StringUtil.removeHtmlTags(it).trim() }.orEmpty()
    }
}
