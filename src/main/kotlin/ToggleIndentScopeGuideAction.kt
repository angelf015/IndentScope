package com.access

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware

/**
 * Toggles the current-scope guide. When disabled, the custom highlighter is removed
 * and each editor gets its native indent-guide setting back.
 */
class ToggleIndentScopeGuideAction : ToggleAction(), DumbAware {

    override fun isSelected(event: AnActionEvent): Boolean =
        IndentScopeService.getInstance().isEnabled(event.project)

    override fun setSelected(event: AnActionEvent, state: Boolean) {
        IndentScopeService.getInstance().setEnabled(event.project, state)
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.text = if (isSelected(event)) {
            IndentScopeBundle.message("action.toggle.hide")
        } else {
            IndentScopeBundle.message("action.toggle.show")
        }
    }
}
