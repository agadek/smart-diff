package io.github.agadek.smartdiff.ui

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.actionSystem.ToggleAction
import io.github.agadek.smartdiff.ui.merge.MergeOutlineController

/**
 * The toolbar button in every diff with an outline (technical specification §10): switches that diff between the
 * outline and the plain native diff. It is in the `Diff.ViewerToolbar` group, which every native viewer adds to its
 * toolbar, and finds its viewer through [DiffDataKeys.DIFF_VIEWER].
 */
class ToggleOutlineAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = target(e) != null
    }

    override fun isSelected(e: AnActionEvent): Boolean = target(e)?.isOutlineVisible == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        target(e)?.setOutlineVisible(state)
    }

    private fun target(e: AnActionEvent): OutlineToggleTarget? {
        val viewer = e.getData(DiffDataKeys.DIFF_VIEWER)
        return OutlineController.of(viewer) ?: MergeOutlineController.of(viewer)
    }
}
