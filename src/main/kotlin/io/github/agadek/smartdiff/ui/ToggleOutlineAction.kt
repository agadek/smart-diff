package io.github.agadek.smartdiff.ui

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.actionSystem.ToggleAction

/**
 * The toolbar button in every diff with an outline (technical specification §10): switches that diff between the
 * outline and the plain native diff. It is in the `Diff.ViewerToolbar` group, which every native viewer adds to its
 * toolbar, and finds its viewer through [DiffDataKeys.DIFF_VIEWER].
 */
class ToggleOutlineAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = controller(e) != null
    }

    override fun isSelected(e: AnActionEvent): Boolean = controller(e)?.isOutlineVisible == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        controller(e)?.setOutlineVisible(state)
    }

    private fun controller(e: AnActionEvent): OutlineController? = OutlineController.of(e.getData(DiffDataKeys.DIFF_VIEWER))
}
