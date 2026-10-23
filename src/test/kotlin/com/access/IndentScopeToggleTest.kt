package com.access

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class IndentScopeToggleTest : BasePlatformTestCase() {

    fun testReenablingRestoresCustomGuide() {
        myFixture.configureByText(
            "Example.txt",
            """
            fun main() {
                if (true) {
                    val x = 1
                }
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))

        val service = ApplicationManager.getApplication().getService(IndentScopeService::class.java)
        PropertiesComponent.getInstance().unsetValue("IndentScope.enabled")
        // The fixture editor is registered by the plugin listener as soon as it is
        // created (with the caret on line 0), so recreate the controller once the
        // caret is inside the block to get a deterministic synchronous update.
        service.editorReleased(editor)
        service.editorCreated(editor)

        assertTrue("Custom guide should be visible when enabled", hasCustomGuide(editor))
        assertFalse("Native guides should be hidden when enabled", editor.settings.isIndentGuidesShown)

        service.enabled = false
        assertFalse("Custom guide should be removed when disabled", hasCustomGuide(editor))
        assertTrue("Native guides should be restored when disabled", editor.settings.isIndentGuidesShown)

        service.enabled = true
        assertTrue("Custom guide should come back when re-enabled", hasCustomGuide(editor))
        assertFalse("Native guides should be hidden again", editor.settings.isIndentGuidesShown)

        service.editorReleased(editor)
    }

    fun testToggleActionCyclesBothWays() {
        myFixture.configureByText(
            "Example.txt",
            """
            fun main() {
                if (true) {
                    val x = 1
                }
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))

        val service = ApplicationManager.getApplication().getService(IndentScopeService::class.java)
        PropertiesComponent.getInstance().unsetValue("IndentScope.enabled")
        service.editorReleased(editor)
        service.editorCreated(editor)

        val action = ToggleIndentScopeGuideAction()
        val event = AnActionEvent.createFromAnAction(
            action,
            null,
            "IndentScopeTest",
            DataContext.EMPTY_CONTEXT,
        )

        action.actionPerformed(event)
        assertFalse("First invocation should disable the guide", service.enabled)
        assertFalse("Guide should be hidden after disabling", hasCustomGuide(editor))
        assertTrue("Native guides should be back after disabling", editor.settings.isIndentGuidesShown)

        action.actionPerformed(event)
        assertTrue("Second invocation should enable the guide", service.enabled)
        assertTrue("Guide should be visible after enabling again", hasCustomGuide(editor))
        assertFalse("Native guides should be hidden after enabling again", editor.settings.isIndentGuidesShown)

        service.editorReleased(editor)
    }

    private fun hasCustomGuide(editor: EditorEx): Boolean =
        editor.markupModel.allHighlighters.any {
            it.customRenderer?.javaClass?.name == "com.access.ActiveIndentGuideRenderer"
        }
}
