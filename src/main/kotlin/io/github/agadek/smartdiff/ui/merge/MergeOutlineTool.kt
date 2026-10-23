package io.github.agadek.smartdiff.ui.merge

import com.intellij.diff.DiffManagerEx
import com.intellij.diff.merge.MergeContext
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.merge.MergeTool
import com.intellij.diff.merge.TextMergeRequest

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
        MergeOutlinePreview.isEnabled() &&
            request is TextMergeRequest &&
            MergeOutlinePreview.supportedLanguage(context.project, request.contents) != null &&
            delegate(context, request) != null

    override fun createComponent(context: MergeContext, request: MergeRequest): MergeTool.MergeViewer {
        val delegate = delegate(context, request) ?: error("no merge tool after SMART Diff accepts ${request.title}")
        val viewer = delegate.createComponent(context, request)
        val textRequest = request as? TextMergeRequest ?: return viewer
        val languageId = MergeOutlinePreview.supportedLanguage(context.project, textRequest.contents) ?: return viewer
        MergeOutlinePreview.attach(viewer.component, MergeOutlinePlaceholder.Kind.MERGE_WINDOW, languageId, textRequest.contentTitles)
        return viewer
    }

    /** The first other merge tool that accepts [request], as the merge window would choose without this one. */
    private fun delegate(context: MergeContext, request: MergeRequest): MergeTool? =
        DiffManagerEx.getInstance().mergeTools.firstOrNull { it !is MergeOutlineTool && it.canShow(context, request) }
}
