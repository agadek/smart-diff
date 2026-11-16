package io.github.agadek.smartdiff.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction

/**
 * "Expand Declaration" in a diff editor's context menu (outline/14 R2, R8): expands the innermost shown row whose range on
 * the caret's side contains the caret. It is registered in `Diff.EditorPopupMenu` with no default shortcut, so a user can
 * assign one in the keymap.
 *
 * The outline controller puts itself in the editor's user data under [OutlineController.EDITOR_KEY]; with no controller,
 * for example in a one-sided or three-sided diff or a diff the outline did not attach to, the action is hidden. It does not
 * select the row in the outline; caret follow does that as usual once the caret moves.
 */
class ExpandDeclarationAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (controller, item) = target(e) ?: return
        controller.expandDeclaration(item)
    }

    /** The controller and the row at the caret when the action applies; null hides it (R8). */
    private fun target(e: AnActionEvent): Pair<OutlineController, OutlineItem>? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val controller = editor.getUserData(OutlineController.EDITOR_KEY) ?: return null
        val side = when (editor) {
            controller.oldEditor -> OutlineController.CaretSide.OLD
            controller.newEditor -> OutlineController.CaretSide.NEW
            else -> return null
        }
        val item = controller.rowAt(side, editor.caretModel.offset) ?: return null
        if (!ExpandDeclaration.wouldExpand(controller.oldEditor, controller.newEditor, item)) return null
        return controller to item
    }
}
