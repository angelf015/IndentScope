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
        IndentScopeService.getInstance().enabled

    override fun setSelected(event: AnActionEvent, state: Boolean) {
        IndentScopeService.getInstance().enabled = state
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.text = if (isSelected(event)) {
            "Hide IndentScope Guide"
        } else {
            "Show IndentScope Guide"
        }
    }
}
