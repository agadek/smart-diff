package io.github.agadek.smartdiff.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.side.ThreesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import io.github.agadek.smartdiff.ui.merge.MergeOutlineController

/**
 * Attaches the structural outline beside the editors of IntelliJ's own side-by-side viewer (technical specification
 * §9.1, ADR 0005).
 *
 * The viewer's component is a `DiffPanelBase` whose `BorderLayout` centre holds the editors. That centre moves into a
 * splitter with the outline as the second component. A request opened from a declaration in the Changes tree (§9.4)
 * carries an [OutlinePath], and the outline selects that row once it is shown. Anything else, such as another viewer or
 * contents that are not two documents of one language, is left alone and shows the native diff only. A language no engine
 * supports still gets the outline, which says so in place of the tree (§10), except plain text: without an engine it is
 * left alone so a mixed change list does not show the message on every text file.
 *
 * A three-sided viewer gets the merge outline (merge outline spec 03), with the middle pane as the base.
 */
class OutlineDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        when (viewer) {
            is TwosideTextDiffViewer -> attachTwoSided(viewer, context, request)
            is ThreesideTextDiffViewer -> attachThreeSided(viewer, context, request)
        }
    }

    private fun attachTwoSided(viewer: TwosideTextDiffViewer, context: DiffContext, request: DiffRequest) {
        val contents = (request as? ContentDiffRequest)?.contents ?: return
        if (contents.size != 2) return
        val old = contents[0] as? DocumentContent ?: return
        val new = contents[1] as? DocumentContent ?: return
        val languageId = new.languageId() ?: return
        if (old.languageId() != languageId) return
        val project = context.project ?: ProjectManager.getInstance().defaultProject
        // Plain text gets no panel unless an engine explicitly supports it, so it does not add noise to every text diff.
        if (languageId == PlainTextLanguage.INSTANCE.id &&
            EngineRegistry.getInstance(project).select(languageId).selected == null
        ) return

        val splitter = insertOutlineSplitter(viewer.component, "smartdiff.outline.proportion") ?: return

        val fileName = (new.highlightFile ?: old.highlightFile)?.name
        // Taken off the request, so showing it again later does not select the row again.
        val reveal = request.getUserData(OutlinePath.KEY)?.also { request.putUserData(OutlinePath.KEY, null) }
        val controller = OutlineController(project, viewer, context, splitter, languageId, fileName, old.document, new.document, reveal)
        Disposer.register(viewer, controller)
        controller.start()
    }

    /** Attaches the merge outline to a three-sided diff; the middle content is the base (merge outline spec 03). */
    private fun attachThreeSided(viewer: ThreesideTextDiffViewer, context: DiffContext, request: DiffRequest) {
        val contentRequest = request as? ContentDiffRequest ?: return
        if (contentRequest.contents.size != 3) return
        val contents = contentRequest.contents.map { it as? DocumentContent ?: return }
        val languageId = contents.map { it.languageId() }.toSet().singleOrNull() ?: return
        val project = context.project ?: ProjectManager.getInstance().defaultProject
        if (EngineRegistry.getInstance(project).select(languageId).selected == null) return

        val splitter = insertOutlineSplitter(viewer.component, "smartdiff.merge.outline.proportion") ?: return
        val fileName = contents.firstNotNullOfOrNull { it.highlightFile }?.name
        val controller = MergeOutlineController.threeSided(
            project,
            viewer,
            context,
            splitter,
            languageId,
            fileName,
            contents[1].document,
            contents[0].document,
            contents[2].document,
            contentRequest.contentTitles,
        )
        Disposer.register(viewer, controller)
        controller.start()
    }
}

/** The language of a document content, from its content type or else its file. */
internal fun DocumentContent.languageId(): String? {
    val fileType = contentType ?: highlightFile?.fileType
    return (fileType as? LanguageFileType)?.language?.id
}
