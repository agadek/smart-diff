package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.base.DiffPanelBase
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * Attaches the structural outline beside the editors of IntelliJ's own side-by-side viewer (technical specification
 * §9.1, ADR 0005).
 *
 * The viewer's component is a [DiffPanelBase] whose `BorderLayout` centre holds the editors. That centre moves into a
 * splitter with the outline as the second component. Anything else, such as another viewer, contents that are not two
 * documents of one language, or a language no engine supports, is left alone and shows the native diff only.
 */
class OutlineDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        if (viewer !is TwosideTextDiffViewer) return
        val contents = (request as? ContentDiffRequest)?.contents ?: return
        if (contents.size != 2) return
        val old = contents[0] as? DocumentContent ?: return
        val new = contents[1] as? DocumentContent ?: return
        val languageId = languageId(new) ?: return
        if (languageId(old) != languageId) return
        val project = context.project ?: ProjectManager.getInstance().defaultProject
        if (EngineRegistry.getInstance(project).select(languageId).selected == null) return

        val panel = viewer.component as? DiffPanelBase ?: return
        val layout = panel.layout as? BorderLayout ?: return
        val editors = layout.getLayoutComponent(BorderLayout.CENTER) as? JComponent ?: return

        val splitter = OnePixelSplitter(false, "smartdiff.outline.proportion", 0.75f)
        splitter.firstComponent = editors
        panel.remove(editors)
        panel.add(splitter, BorderLayout.CENTER)

        val fileName = (new.highlightFile ?: old.highlightFile)?.name
        val controller = OutlineController(project, viewer, context, splitter, languageId, fileName, old.document, new.document)
        Disposer.register(viewer, controller)
        controller.start()
    }

    private fun languageId(content: DocumentContent): String? {
        val fileType = content.contentType ?: content.highlightFile?.fileType
        return (fileType as? LanguageFileType)?.language?.id
    }
}
