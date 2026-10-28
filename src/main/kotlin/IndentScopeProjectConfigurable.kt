package com.access

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.layout.not
import java.awt.Color
import javax.swing.JComponent

/**
 * Settings → Tools → IndentScope (Project). By default the project inherits the
 * global values. Unchecking the inheritance checkbox turns this panel into a full
 * project-level override, and the controls stay disabled while the project inherits.
 */
class IndentScopeProjectConfigurable(
    private val project: Project,
) : SearchableConfigurable {

    private var panel: DialogPanel? = null
    private var colorPanel: ColorPanel? = null
    private val draft = IndentScopeProjectSettings.State()
    private val projectSettings: IndentScopeProjectSettings
        get() = IndentScopeProjectSettings.getInstance(project)

    override fun getId(): String = "com.access.IndentScope.projectSettings"

    override fun getDisplayName(): String = IndentScopeBundle.message("settings.projectDisplayName")

    override fun createComponent(): JComponent {
        copyIntoDraft(projectSettings.getState())

        val colorChooser = ColorPanel().apply {
            selectedColor = Color(draft.overrides.customColorRgb)
            addActionListener {
                selectedColor?.let { draft.overrides.customColorRgb = it.rgb }
            }
        }
        colorPanel = colorChooser

        val created = panel {
            lateinit var useGlobal: Cell<JBCheckBox>
            group(IndentScopeBundle.message("settings.group.project")) {
                row {
                    useGlobal = checkBox(IndentScopeBundle.message("settings.useGlobalSettings"))
                        .bindSelected(draft::useGlobalSettings)
                }
                row {
                    button(IndentScopeBundle.message("settings.copyGlobalSettings")) {
                        copyGlobalSettings()
                    }
                }
                row {
                    comment(IndentScopeBundle.message("settings.projectHint"))
                }
            }
            panel {
                indentScopeSettingsGroups(draft.overrides, colorChooser)
            }.enabledIf(!useGlobal.selected)
        }

        panel = created
        return created
    }

    override fun isModified(): Boolean {
        val current = panel ?: return false
        if (current.isModified()) return true
        return draft.overrides.customColorRgb != projectSettings.getState().overrides.customColorRgb
    }

    override fun apply() {
        val current = panel ?: return
        current.apply()
        colorPanel?.selectedColor?.let { draft.overrides.customColorRgb = it.rgb }

        if (!draft.useGlobalSettings &&
            !draft.overrides.showBraceScopes &&
            !draft.overrides.showParenScopes &&
            !draft.overrides.showBracketScopes
        ) {
            throw ConfigurationException(IndentScopeBundle.message("settings.error.noScopeTypes"))
        }

        projectSettings.apply(
            IndentScopeProjectSettings.State(
                useGlobalSettings = draft.useGlobalSettings,
                overrides = draft.overrides.copy(),
            ),
        )
    }

    override fun reset() {
        copyIntoDraft(projectSettings.getState())
        colorPanel?.selectedColor = Color(draft.overrides.customColorRgb)
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
        colorPanel = null
    }

    private fun copyGlobalSettings() {
        draft.useGlobalSettings = false
        draft.overrides.copyFrom(IndentScopeSettings.getInstance().getState())
        colorPanel?.selectedColor = Color(draft.overrides.customColorRgb)
        panel?.reset()
    }

    private fun copyIntoDraft(source: IndentScopeProjectSettings.State) {
        draft.useGlobalSettings = source.useGlobalSettings
        draft.overrides.copyFrom(source.overrides)
    }
}
