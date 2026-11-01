package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.DiffManagerEx
import com.intellij.diff.merge.MergeContext
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.merge.MergeTool
import com.intellij.diff.merge.TextMergeRequest
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import io.github.agadek.smartdiff.ui.insertOutlineSplitter
import io.github.agadek.smartdiff.ui.removeOutlineSplitter

/**
 * Attaches to the merge window (ADR 0007): a merge tool registered first that lets the next tool accepting the request,
 * normally the platform's text merge tool, create the viewer, then puts the outline beside that viewer's editors and
 * returns the viewer itself.
 *
 * The merge window runs no `DiffExtension`, so the extension that serves two- and three-sided diffs cannot reach it.
 * Returning the platform's own viewer object keeps everything that looks it up by type working, such as Annotate and
 * the automatic import resolution in the merge window. The platform's tool is found through the public tool list, never
 * by its class, which is internal API.
 */
class MergeOutlineTool : MergeTool {

    override fun canShow(context: MergeContext, request: MergeRequest): Boolean =
        request is TextMergeRequest &&
            MergeWindowSupport.languageId(context.project, request.contents) != null &&
            delegate(context, request) != null

    override fun createComponent(context: MergeContext, request: MergeRequest): MergeTool.MergeViewer {
        val delegate = delegate(context, request) ?: error("no merge tool after SMART Diff accepts ${request.title}")
        val viewer = delegate.createComponent(context, request)
        if (request is TextMergeRequest) attach(context, request, viewer)
        return viewer
    }

    /** Attaches the outline in place; when the layout or the result pane is unavailable, the native merge window stays. */
    private fun attach(context: MergeContext, request: TextMergeRequest, viewer: MergeTool.MergeViewer) {
        val project = context.project ?: ProjectManager.getInstance().defaultProject
        val languageId = MergeWindowSupport.languageId(project, request.contents) ?: return
        var splitter: OnePixelSplitter? = null
        var controller: MergeOutlineController? = null
        var result: MergeResultPane? = null
        try {
            splitter = insertOutlineSplitter(viewer.component, PROPORTION_KEY) ?: return
            val contents = request.contents
            val baseDocument = contents[1].document
            result = MergeResultPane(project, contents[0].document, baseDocument, contents[2].document, request.outputContent.document)
            val (leftName, rightName) = MergeWindowSupport.names(request)
            controller = MergeOutlineController.mergeWindow(
                project,
                viewer,
                context,
                splitter,
                languageId,
                fileName(request),
                baseDocument,
                contents[0].document,
                contents[2].document,
                leftName,
                rightName,
                result,
            )
            Disposer.register(controller, result)
            Disposer.register(viewer, controller)
            controller.start()
        } catch (e: RuntimeException) {
            // The native merge window shows as usual when the outline cannot attach (merge window spec R3).
            controller?.let { Disposer.dispose(it) } ?: result?.let { Disposer.dispose(it) }
            splitter?.let(::removeOutlineSplitter)
        }
    }

    private fun fileName(request: TextMergeRequest): String? =
        request.contents.firstNotNullOfOrNull { it.highlightFile?.name }

    /** The first other merge tool that accepts [request], as the merge window would choose without this one. */
    private fun delegate(context: MergeContext, request: MergeRequest): MergeTool? =
        DiffManagerEx.getInstance().mergeTools.firstOrNull { it !is MergeOutlineTool && it.canShow(context, request) }

    private companion object {
        const val PROPORTION_KEY = "smartdiff.merge.outline.proportion"
    }
}
