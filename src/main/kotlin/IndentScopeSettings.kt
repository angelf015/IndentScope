package com.access

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.project.Project
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.util.messages.Topic

/**
 * Application-wide settings for IndentScope. The state is persisted in
 * `indentScope.xml` instead of [PropertiesComponent], which keeps every option in
 * one place and lets future versions add settings without a migration step.
 */
@State(
    name = "IndentScopeSettings",
    storages = [Storage("indentScope.xml")],
    category = SettingsCategory.TOOLS,
)
@Service(Service.Level.APP)
class IndentScopeSettings : PersistentStateComponent<IndentScopeSettings.State> {

    /**
     * Plain mutable state. The default XML serializer maps every public property to
     * an element, so new options get a safe default when an older file is loaded.
     */
    data class State(
        var enabled: Boolean = true,
        var keepNativeGuides: Boolean = false,
        var showOnlyInActiveEditor: Boolean = false,
        var showColumnZeroGuide: Boolean = false,
        var showEnclosingScopes: Boolean = false,
        var maxEnclosingScopes: Int = DEFAULT_MAX_ENCLOSING_SCOPES,
        var animateScopeChanges: Boolean = false,
        var showBraceScopes: Boolean = true,
        var showParenScopes: Boolean = true,
        var showBracketScopes: Boolean = true,
        var showInConsoleEditors: Boolean = false,
        var showInDiffEditors: Boolean = false,
        var showInPreviewEditors: Boolean = false,
        var strokeWidth: Double = DEFAULT_STROKE_WIDTH,
        var cornerRadius: Int = DEFAULT_CORNER_RADIUS,
        var customColorEnabled: Boolean = false,
        var customColorRgb: Int = DEFAULT_CUSTOM_COLOR_RGB,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = normalize(state)
    }

    override fun noStateLoaded() {
        // 1.2.1 and older persisted only the global toggle. Keep the user's choice
        // when the new settings file is created for the first time.
        val properties = PropertiesComponent.getInstance()
        val legacyValue = properties.getValue(LEGACY_ENABLED_KEY) ?: return
        myState.enabled = legacyValue.toBoolean()
        properties.unsetValue(LEGACY_ENABLED_KEY)
    }

    /**
     * Copies [state] into this service and notifies open editors. The configurable
     * edits a draft and calls this only when the user presses Apply/OK.
     */
    fun apply(state: State) {
        myState = normalize(state.copy())
        notifyChanged()
    }

    var enabled: Boolean
        get() = myState.enabled
        set(value) {
            if (myState.enabled == value) return
            myState.enabled = value
            notifyChanged()
        }

    /**
     * Effective settings for [project]. A project whose settings are inherited uses
     * the application state; application-level editors keep using it as well.
     */
    fun stateFor(project: Project?): State = projectState(project) ?: myState

    fun isEnabled(project: Project?): Boolean = stateFor(project).enabled

    /**
     * Toggles the state at the narrowest scope: a project with its own settings
     * updates its override, while an inherited project updates the global state.
     */
    fun setEnabled(project: Project?, value: Boolean) {
        if (project == null || project.isDisposed) {
            enabled = value
            return
        }

        val projectSettings = try {
            IndentScopeProjectSettings.getInstance(project)
        } catch (e: AlreadyDisposedException) {
            // The project can close between the action event and this lookup.
            enabled = value
            return
        }
        if (projectSettings.getState().useGlobalSettings) {
            enabled = value
        } else {
            projectSettings.setEnabled(value)
        }
    }

    fun allowedOpeners(project: Project? = null): Set<Char> = buildSet {
        val state = stateFor(project)
        if (state.showBraceScopes) add('{')
        if (state.showParenScopes) add('(')
        if (state.showBracketScopes) add('[')
    }

    fun shouldShowInEditor(kind: EditorKind, project: Project? = null): Boolean {
        val state = stateFor(project)
        return isEditorKindEnabled(
            kind = kind,
            showInConsoleEditors = state.showInConsoleEditors,
            showInDiffEditors = state.showInDiffEditors,
            showInPreviewEditors = state.showInPreviewEditors,
        )
    }

    private fun projectState(project: Project?): State? {
        if (project == null || project.isDisposed) return null
        return try {
            IndentScopeProjectSettings.getInstance(project).effectiveState()
        } catch (e: AlreadyDisposedException) {
            // The project can close between the editor event and this lookup.
            null
        }
    }

    private fun notifyChanged() = publishIndentScopeSettingsChanged()

    private fun normalize(state: State): State = normalizeState(state)

    companion object {
        const val MIN_STROKE_WIDTH = 1.0
        const val MAX_STROKE_WIDTH = 4.0
        const val MIN_CORNER_RADIUS = 0
        const val MAX_CORNER_RADIUS = 12
        const val MIN_ENCLOSING_SCOPES = 1
        const val MAX_ENCLOSING_SCOPES = 10
        const val DEFAULT_STROKE_WIDTH = 1.5
        const val DEFAULT_CORNER_RADIUS = 4
        const val DEFAULT_MAX_ENCLOSING_SCOPES = 3
        const val DEFAULT_CUSTOM_COLOR_RGB = 0x4A88C7

        private const val LEGACY_ENABLED_KEY = "IndentScope.enabled"

        internal fun normalizeState(state: State): State = state.apply {
            strokeWidth = strokeWidth.coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)
            cornerRadius = cornerRadius.coerceIn(MIN_CORNER_RADIUS, MAX_CORNER_RADIUS)
            maxEnclosingScopes = maxEnclosingScopes.coerceIn(MIN_ENCLOSING_SCOPES, MAX_ENCLOSING_SCOPES)
            customColorRgb = customColorRgb and 0xFFFFFF
        }

        fun getInstance(): IndentScopeSettings =
            ApplicationManager.getApplication().getService(IndentScopeSettings::class.java)
    }
}

/**
 * Copies every persisted option into [target]. The configurable and the project
 * settings panel use it to keep their drafts in sync without listing options twice.
 */
internal fun IndentScopeSettings.State.copyFrom(source: IndentScopeSettings.State) {
    enabled = source.enabled
    keepNativeGuides = source.keepNativeGuides
    showOnlyInActiveEditor = source.showOnlyInActiveEditor
    showColumnZeroGuide = source.showColumnZeroGuide
    showEnclosingScopes = source.showEnclosingScopes
    maxEnclosingScopes = source.maxEnclosingScopes
    animateScopeChanges = source.animateScopeChanges
    showBraceScopes = source.showBraceScopes
    showParenScopes = source.showParenScopes
    showBracketScopes = source.showBracketScopes
    showInConsoleEditors = source.showInConsoleEditors
    showInDiffEditors = source.showInDiffEditors
    showInPreviewEditors = source.showInPreviewEditors
    strokeWidth = source.strokeWidth
    cornerRadius = source.cornerRadius
    customColorEnabled = source.customColorEnabled
    customColorRgb = source.customColorRgb
}

internal fun publishIndentScopeSettingsChanged() {
    val application = ApplicationManager.getApplication() ?: return
    application.messageBus.syncPublisher(IndentScopeSettingsListener.TOPIC).settingsChanged()
}

/**
 * Published after settings are applied so every open editor can rebuild its guide
 * and update the native-indent-guide policy immediately.
 */
internal interface IndentScopeSettingsListener {
    fun settingsChanged()

    companion object {
        val TOPIC: Topic<IndentScopeSettingsListener> =
            Topic.create("IndentScope settings changed", IndentScopeSettingsListener::class.java)
    }
}
