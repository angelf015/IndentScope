package com.access

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.dsl.builder.panel
import java.awt.Color
import javax.swing.JComponent

/**
 * Settings → Tools → IndentScope. The panel edits a draft copy; nothing is applied
 * until the user confirms, so Cancel keeps the current guide untouched.
 */
class IndentScopeConfigurable : SearchableConfigurable {

    private var panel: DialogPanel? = null
    private var colorPanel: ColorPanel? = null
    private val draft = IndentScopeSettings.State()
    private val settings: IndentScopeSettings
        get() = IndentScopeSettings.getInstance()

    override fun getId(): String = "com.access.IndentScope.settings"

    override fun getDisplayName(): String = IndentScopeBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        draft.copyFrom(settings.getState())

        val colorChooser = ColorPanel().apply {
            selectedColor = Color(draft.customColorRgb)
            addActionListener {
                selectedColor?.let { draft.customColorRgb = it.rgb }
            }
        }
        colorPanel = colorChooser

        val created = panel {
            indentScopeSettingsGroups(draft, colorChooser)
        }

        panel = created
        return created
    }

    override fun isModified(): Boolean {
        val current = panel
        if (current == null) return false

        if (current.isModified()) return true
        return draft.customColorRgb != settings.getState().customColorRgb
    }

    override fun apply() {
        val current = panel ?: return
        current.apply()
        colorPanel?.selectedColor?.let { draft.customColorRgb = it.rgb }

        if (!draft.showBraceScopes && !draft.showParenScopes && !draft.showBracketScopes) {
            throw ConfigurationException(IndentScopeBundle.message("settings.error.noScopeTypes"))
        }

        settings.apply(draft)
    }

    override fun reset() {
        draft.copyFrom(settings.getState())
        colorPanel?.selectedColor = Color(draft.customColorRgb)
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
        colorPanel = null
    }
}
