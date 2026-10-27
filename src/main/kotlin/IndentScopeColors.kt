package com.access

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Theme-aware guide color. The key is exposed in Settings → Editor → Color Scheme →
 * IndentScope and can be customized per color scheme. When IndentScope uses a custom
 * color in its own settings, that color takes precedence over the scheme.
 */
internal object IndentScopeColors {

    @Suppress("DEPRECATION")
    val GUIDE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "INDENT_SCOPE_GUIDE",
        TextAttributes().apply {
            foregroundColor = JBColor(DEFAULT_LIGHT, DEFAULT_DARK)
        },
    )

    fun guideColor(scheme: EditorColorsScheme): Color? =
        scheme.getAttributes(GUIDE)?.foregroundColor
            ?: scheme.getColor(EditorColors.SELECTED_INDENT_GUIDE_COLOR)
            ?: scheme.getColor(EditorColors.INDENT_GUIDE_COLOR)

    private const val DEFAULT_LIGHT = 0x4A88C7
    private const val DEFAULT_DARK = 0x7A9CC6
}
