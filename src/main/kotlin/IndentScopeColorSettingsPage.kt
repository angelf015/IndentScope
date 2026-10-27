package com.access

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Adds IndentScope to Settings → Editor → Color Scheme so the guide color can
 * follow the active theme or be customized per color scheme.
 */
class IndentScopeColorSettingsPage : ColorSettingsPage {

    override fun getIcon(): Icon =
        IconLoader.getIcon("/META-INF/pluginIcon.svg", IndentScopeColorSettingsPage::class.java)

    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    override fun getDemoText(): String = """
        <guide>fun main() {</guide>
            if (ready) {
                doWork()
            }
        }
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
        mapOf("guide" to IndentScopeColors.GUIDE)

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> =
        arrayOf(
            AttributesDescriptor(
                IndentScopeBundle.message("colors.guide"),
                IndentScopeColors.GUIDE,
            ),
        )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = IndentScopeBundle.message("colors.displayName")
}
