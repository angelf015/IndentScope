package com.access

import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.selected

/**
 * Shared rows of the application and project settings panels. Keeping them in one
 * place guarantees that both scopes expose exactly the same options and defaults.
 */
internal fun Panel.indentScopeSettingsGroups(
    draft: IndentScopeSettings.State,
    colorChooser: ColorPanel,
) {
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
        row {
            checkBox(IndentScopeBundle.message("settings.showColumnZeroGuide"))
                .bindSelected(draft::showColumnZeroGuide)
        }

        lateinit var showEnclosingScopes: Cell<JBCheckBox>
        row {
            showEnclosingScopes = checkBox(IndentScopeBundle.message("settings.showEnclosingScopes"))
                .bindSelected(draft::showEnclosingScopes)
        }
        row(IndentScopeBundle.message("settings.maxEnclosingScopes")) {
            spinner(IndentScopeSettings.MIN_ENCLOSING_SCOPES..IndentScopeSettings.MAX_ENCLOSING_SCOPES)
                .bindIntValue(draft::maxEnclosingScopes)
                .enabledIf(showEnclosingScopes.selected)
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
        row {
            checkBox(IndentScopeBundle.message("settings.animateScopeChanges"))
                .bindSelected(draft::animateScopeChanges)
        }
        row {
            comment(IndentScopeBundle.message("settings.animationHint"))
        }
    }
}
