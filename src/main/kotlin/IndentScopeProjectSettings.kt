package com.access

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Per-project override of the application settings. Projects inherit the global
 * values by default, so existing installations keep their behavior after upgrading.
 *
 * The project state is stored in `.idea/indentScopeProject.xml`. It is a separate
 * file from the application `indentScope.xml` and can be committed with the project
 * when a team wants to share the same guide configuration.
 */
@State(
    name = "IndentScopeProjectSettings",
    storages = [Storage("indentScopeProject.xml")],
    category = SettingsCategory.TOOLS,
)
@Service(Service.Level.PROJECT)
class IndentScopeProjectSettings : PersistentStateComponent<IndentScopeProjectSettings.State> {

    data class State(
        var useGlobalSettings: Boolean = true,
        var overrides: IndentScopeSettings.State = IndentScopeSettings.State(),
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = normalize(state)
    }

    /**
     * Copies [state] into the project service and notifies every open editor. The
     * configurable edits a draft and calls this only on Apply/OK.
     */
    fun apply(state: State) {
        myState = normalize(state.copy(overrides = state.overrides.copy()))
        publishIndentScopeSettingsChanged()
    }

    /**
     * Settings that editors in this project must use. Inheriting projects return the
     * live application state; the caller treats it as read-only.
     */
    fun effectiveState(): IndentScopeSettings.State =
        if (myState.useGlobalSettings) IndentScopeSettings.getInstance().getState() else myState.overrides

    /**
     * Updates the project override. The public toggle entry point is
     * [IndentScopeSettings.setEnabled], which also handles inheritance.
     */
    internal fun setEnabled(value: Boolean) {
        if (myState.useGlobalSettings) {
            IndentScopeSettings.getInstance().enabled = value
            return
        }
        if (myState.overrides.enabled == value) return
        myState.overrides.enabled = value
        publishIndentScopeSettingsChanged()
    }

    private fun normalize(state: State): State = state.apply {
        overrides = IndentScopeSettings.normalizeState(overrides)
    }

    companion object {
        fun getInstance(project: Project): IndentScopeProjectSettings =
            project.getService(IndentScopeProjectSettings::class.java)
    }
}
