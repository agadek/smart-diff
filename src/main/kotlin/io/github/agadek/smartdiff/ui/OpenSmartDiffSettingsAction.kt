package io.github.agadek.smartdiff.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/** Opens Settings | Tools | SMART Diff for the diff's project (outline/10). */
class OpenSmartDiffSettingsAction : DumbAwareAction(
    "SMART Diff Settings",
    "Open Settings | Tools | SMART Diff",
    AllIcons.General.GearPlain,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        settingsOpener(e.project)
    }

    companion object {
        /**
         * Opens the SMART Diff settings page for [project]; replaced in tests so no modal dialog is shown.
         */
        internal var settingsOpener: (Project?) -> Unit = { project ->
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SmartDiffConfigurable::class.java)
        }
    }
}
