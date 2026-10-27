package com.access

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
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
        copyIntoDraft(settings.getState())

        val colorChooser = ColorPanel().apply {
            selectedColor = Color(draft.customColorRgb)
            addActionListener {
                selectedColor?.let { draft.customColorRgb = it.rgb }
            }
        }
        colorPanel = colorChooser

        val created = panel {
            group(IndentScopeBundle.message("settings.group.general")) {
                row {
                    checkBox(IndentScopeBundle.message("settings.enabled"))
                        .bindSelected(draft::enabled)
                }
                row {
                    checkBox(IndentScopeBundle.message("settings.keepNativeGuides"))
                        .bindSelected(draft::keepNativeGuides)
                }
                row {
                    checkBox(IndentScopeBundle.message("settings.showOnlyInActiveEditor"))
                        .bindSelected(draft::showOnlyInActiveEditor)
                }
            }
            group(IndentScopeBundle.message("settings.group.scopeTypes")) {
                row {
                    checkBox(IndentScopeBundle.message("settings.scopeBlocks"))
                        .bindSelected(draft::showBraceScopes)
                }
                row {
                    checkBox(IndentScopeBundle.message("settings.scopeParentheses"))
                        .bindSelected(draft::showParenScopes)
                }
                row {
                    checkBox(IndentScopeBundle.message("settings.scopeBrackets"))
                        .bindSelected(draft::showBracketScopes)
                }
            }
            group(IndentScopeBundle.message("settings.group.editorTypes")) {
                row {
                    checkBox(IndentScopeBundle.message("settings.showInConsoleEditors"))
                        .bindSelected(draft::showInConsoleEditors)
                }
                row {
                    checkBox(IndentScopeBundle.message("settings.showInDiffEditors"))
                        .bindSelected(draft::showInDiffEditors)
                }
                row {
                    checkBox(IndentScopeBundle.message("settings.showInPreviewEditors"))
                        .bindSelected(draft::showInPreviewEditors)
                }
                row {
                    comment(IndentScopeBundle.message("settings.editorTypesHint"))
                }
            }
            group(IndentScopeBundle.message("settings.group.appearance")) {
                row(IndentScopeBundle.message("settings.strokeWidth")) {
                    spinner(IndentScopeSettings.MIN_STROKE_WIDTH..IndentScopeSettings.MAX_STROKE_WIDTH, 0.5)
                        .bindValue(draft::strokeWidth)
                        .comment(IndentScopeBundle.message("settings.pixels"))
                }
                row(IndentScopeBundle.message("settings.cornerRadius")) {
                    spinner(IndentScopeSettings.MIN_CORNER_RADIUS..IndentScopeSettings.MAX_CORNER_RADIUS)
                        .bindIntValue(draft::cornerRadius)
                        .comment(IndentScopeBundle.message("settings.pixels"))
                }
                row {
                    val customColor = checkBox(IndentScopeBundle.message("settings.customColor"))
                        .bindSelected(draft::customColorEnabled)
                    cell(colorChooser).enabledIf(customColor.selected)
                }
                row {
                    comment(IndentScopeBundle.message("settings.colorHint"))
                }
            }
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
        copyIntoDraft(settings.getState())
        colorPanel?.selectedColor = Color(draft.customColorRgb)
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
        colorPanel = null
    }

    private fun copyIntoDraft(source: IndentScopeSettings.State) {
        draft.enabled = source.enabled
        draft.keepNativeGuides = source.keepNativeGuides
        draft.showOnlyInActiveEditor = source.showOnlyInActiveEditor
        draft.showBraceScopes = source.showBraceScopes
        draft.showParenScopes = source.showParenScopes
        draft.showBracketScopes = source.showBracketScopes
        draft.showInConsoleEditors = source.showInConsoleEditors
        draft.showInDiffEditors = source.showInDiffEditors
        draft.showInPreviewEditors = source.showInPreviewEditors
        draft.strokeWidth = source.strokeWidth
        draft.cornerRadius = source.cornerRadius
        draft.customColorEnabled = source.customColorEnabled
        draft.customColorRgb = source.customColorRgb
    }
}
