package io.github.agadek.smartdiff.ui.changes

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareToggleAction
import io.github.agadek.smartdiff.ui.ChangeType
import io.github.agadek.smartdiff.ui.SmartDiffSettings

/** The Changes tree's "Structural Summary" toggle (changes-tree/04 R2, R3, R5). */
class ChangesTreeStructuralSummaryAction : DumbAwareToggleAction(
    "Structural Summary",
    "Show the SMART Diff summary after each changed file",
    null,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = SmartDiffSettings.getInstance().showChangesSummary

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        SmartDiffSettings.getInstance().showChangesSummary = state
        notifyChangesTreeSettingsChanged()
    }
}

/** The Changes tree's "Declarations" toggle (changes-tree/04 R2, R4, R5). */
class ChangesTreeDeclarationsAction : DumbAwareToggleAction(
    "Declarations",
    "Show changed declarations under each file in the Changes tree",
    null,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = SmartDiffSettings.getInstance().showChangesDeclarations

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        SmartDiffSettings.getInstance().showChangesDeclarations = state
        notifyChangesTreeSettingsChanged()
    }
}

/** The "Hide Change Types" submenu of the tree's own filter (changes-tree/04 R7 to R12, R16, R17). */
class ChangesTreeHideChangeTypesGroup : DefaultActionGroup("Hide Change Types", true) {
    init {
        templatePresentation.description = "Hide types of changes from the Changes tree"
        templatePresentation.icon = AllIcons.General.Filter
        ChangeType.entries.forEach { add(HideChangeTypeAction(it)) }
    }

    private class HideChangeTypeAction(private val type: ChangeType) : DumbAwareToggleAction(type.text) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean = type in SmartDiffSettings.getInstance().hiddenChangesTreeChangeTypes

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            val settings = SmartDiffSettings.getInstance()
            settings.hiddenChangesTreeChangeTypes =
                if (state) settings.hiddenChangesTreeChangeTypes + type else settings.hiddenChangesTreeChangeTypes - type
            notifyChangesTreeSettingsChanged()
        }
    }
}

/**
 * Puts the tree's actions into the eye-icon View Options menu. The 2025.1 platform declares the public
 * `ChangesView.ViewOptions` group there; a build without that group, such as the 2026.3 Changes view, gets the same
 * group in `ChangesViewToolbar` instead (changes-tree/04 R1). The placement is idempotent and never wraps the tree.
 */
internal object ChangesTreeActions {
    const val GROUP_ID = "SmartDiff.ChangesTree"
    const val VIEW_OPTIONS_GROUP_ID = "ChangesView.ViewOptions"
    const val TOOLBAR_GROUP_ID = "ChangesViewToolbar"

    /** Installs the actions once the platform has registered its action groups, on the UI thread. */
    fun install() {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) installNow() else application.invokeLater { installNow() }
    }

    /** Installs the actions now; safe to call more than once and from tests. */
    internal fun installNow(actionManager: ActionManager = ActionManager.getInstance()) {
        val group = actionManager.getAction(GROUP_ID) as? DefaultActionGroup ?: return
        val viewOptions = actionManager.getAction(VIEW_OPTIONS_GROUP_ID) as? DefaultActionGroup
        val toolbar = actionManager.getAction(TOOLBAR_GROUP_ID) as? DefaultActionGroup
        place(group, viewOptions, toolbar)
    }

    /**
     * Adds [group] to the View Options group, or to the toolbar when that group is absent or rejects it. Returns whether
     * a parent was found.
     */
    internal fun place(group: DefaultActionGroup, viewOptions: DefaultActionGroup?, toolbar: DefaultActionGroup?): Boolean {
        if (viewOptions != null && add(viewOptions, group)) return true
        return toolbar != null && add(toolbar, group)
    }

    private fun add(parent: DefaultActionGroup, group: DefaultActionGroup): Boolean = try {
        if (!parent.containsAction(group)) parent.add(group)
        true
    } catch (e: Exception) {
        false
    }
}

/** Rebuilds every open Changes tree after one of its toggles or its filter changes (changes-tree/04 R6, R18). */
internal fun notifyChangesTreeSettingsChanged() {
    ApplicationManager.getApplication().messageBus.syncPublisher(SmartDiffSettings.CHANGED).settingsChanged()
}
