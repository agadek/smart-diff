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
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import io.github.agadek.smartdiff.ui.merge.MergeOutlinePreview

/**
 * Attaches the structural outline beside the editors of IntelliJ's own side-by-side viewer (technical specification
 * §9.1, ADR 0005).
 *
 * The viewer's component is a `DiffPanelBase` whose `BorderLayout` centre holds the editors. That centre moves into a
 * splitter with the outline as the second component. A request opened from a declaration in the Changes tree (§9.4)
 * carries an [OutlinePath], and the outline selects that row once it is shown. Anything else, such as another viewer or
 * contents that are not two documents of one language, is left alone and shows the native diff only. A language no engine
 * supports still gets the outline, which says so in place of the tree (§10).
 *
 * A three-sided viewer gets the merge outline placeholder of spike S6 while its preview is switched on
 * ([MergeOutlinePreview], three-way merge plan §6.1).
 */
class OutlineDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        when (viewer) {
            is TwosideTextDiffViewer -> attachTwoSided(viewer, context, request)
            is ThreesideTextDiffViewer -> MergeOutlinePreview.attachToThreeSidedDiff(viewer, context, request)
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

        val splitter = insertOutlineSplitter(viewer.component, "smartdiff.outline.proportion") ?: return

        val fileName = (new.highlightFile ?: old.highlightFile)?.name
        // Taken off the request, so showing it again later does not select the row again.
        val reveal = request.getUserData(OutlinePath.KEY)?.also { request.putUserData(OutlinePath.KEY, null) }
        val controller = OutlineController(project, viewer, context, splitter, languageId, fileName, old.document, new.document, reveal)
        Disposer.register(viewer, controller)
        controller.start()
    }
}

/** The language of a document content, from its content type or else its file. */
internal fun DocumentContent.languageId(): String? {
    val fileType = contentType ?: highlightFile?.fileType
    return (fileType as? LanguageFileType)?.language?.id
}
