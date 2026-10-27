package com.access

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
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
        var showBraceScopes: Boolean = true,
        var showParenScopes: Boolean = true,
        var showBracketScopes: Boolean = true,
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

    fun allowedOpeners(): Set<Char> = buildSet {
        if (myState.showBraceScopes) add('{')
        if (myState.showParenScopes) add('(')
        if (myState.showBracketScopes) add('[')
    }

    private fun notifyChanged() {
        val application = ApplicationManager.getApplication() ?: return
        application.messageBus.syncPublisher(IndentScopeSettingsListener.TOPIC).settingsChanged()
    }

    private fun normalize(state: State): State = state.apply {
        strokeWidth = strokeWidth.coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)
        cornerRadius = cornerRadius.coerceIn(MIN_CORNER_RADIUS, MAX_CORNER_RADIUS)
        customColorRgb = customColorRgb and 0xFFFFFF
    }

    companion object {
        const val MIN_STROKE_WIDTH = 1.0
        const val MAX_STROKE_WIDTH = 4.0
        const val MIN_CORNER_RADIUS = 0
        const val MAX_CORNER_RADIUS = 12
        const val DEFAULT_STROKE_WIDTH = 1.5
        const val DEFAULT_CORNER_RADIUS = 4
        const val DEFAULT_CUSTOM_COLOR_RGB = 0x4A88C7

        private const val LEGACY_ENABLED_KEY = "IndentScope.enabled"

        fun getInstance(): IndentScopeSettings =
            ApplicationManager.getApplication().getService(IndentScopeSettings::class.java)
    }
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
