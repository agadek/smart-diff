package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
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
 * The placeholder of spike S6 for the merge window outline (docs/THREE-WAY-MERGE.md, phase 0, ADR 0007): it shows where
 * the outline will sit and which side titles it receives. It is off unless the registry key [REGISTRY_KEY] is on, and is
 * replaced by the outline in the merge-window phase.
 */
internal object MergeOutlinePreview {

    const val REGISTRY_KEY = "smartdiff.merge.outline.preview"

    private const val PROPORTION_KEY = "smartdiff.merge.outline.proportion"

    fun isEnabled(): Boolean = Registry.`is`(REGISTRY_KEY, false)

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
    fun attach(component: JComponent, languageId: String, titles: List<String?>): MergeOutlinePlaceholder? {
        val splitter = insertOutlineSplitter(component, PROPORTION_KEY) ?: return null
        val placeholder = MergeOutlinePlaceholder(languageId, titles.getOrNull(0), titles.getOrNull(2))
        splitter.secondComponent = placeholder
        return placeholder
    }
}

/** What the merge window outline will show: the language and both sides' titles, without markup. */
internal class MergeOutlinePlaceholder(val languageId: String, leftTitle: String?, rightTitle: String?) : JPanel(BorderLayout()) {

    val leftTitle: String = plain(leftTitle)
    val rightTitle: String = plain(rightTitle)

    init {
        val label = JBLabel(
            "<html>SMART Diff merge outline preview<br>merge window, $languageId<br><br>" +
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
