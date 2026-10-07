package ai.opencode.ide.jetbrains

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware

/**
 * Tools → OpenCode → "Auto-open Diff Review".
 *
 * When selected, the multi-file diff viewer opens automatically when a task
 * completes. When deselected (default), a "Review changes" notification action
 * is offered instead. Persisted per project via PropertiesComponent.
 */
class ToggleAutoDiffReviewAction : ToggleAction(
    "Auto-open Diff Review",
    "Automatically open the diff viewer when OpenCode finishes a task",
    null
), DumbAware {

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return PropertiesComponent.getInstance(project).getBoolean(OpenCodeService.AUTO_OPEN_DIFF_REVIEW_KEY, false)
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        PropertiesComponent.getInstance(project).setValue(OpenCodeService.AUTO_OPEN_DIFF_REVIEW_KEY, state)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
