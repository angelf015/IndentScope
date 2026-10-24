package com.access

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class IndentScopeToggleTest : BasePlatformTestCase() {

    private val service: IndentScopeService
        get() = ApplicationManager.getApplication().getService(IndentScopeService::class.java)

    fun testNativeIndentGuideSettingIsPerEditor() {
        myFixture.configureByText("Example.txt", "a\nb\n")
        val first = myFixture.editor as EditorEx
        val second = EditorFactory.getInstance().createEditor(first.document, project) as EditorEx
        try {
            first.settings.isIndentGuidesShown = true
            second.settings.isIndentGuidesShown = true
            first.settings.isIndentGuidesShown = false
            assertTrue(
                "Changing one editor's setting must not affect another editor",
                second.settings.isIndentGuidesShown,
            )
        } finally {
            first.settings.isIndentGuidesShown = true
            second.settings.isIndentGuidesShown = true
            service.editorReleased(first)
            service.editorReleased(second)
            EditorFactory.getInstance().releaseEditor(second)
        }
    }

    fun testReenablingRestoresCustomGuide() {
        val editor = configureBlockEditor()
        activate(editor)
        try {
            assertTrue("Custom guide should be visible when enabled", hasCustomGuide(editor))
            assertFalse("Native guides should be hidden when enabled", editor.settings.isIndentGuidesShown)

            service.enabled = false
            assertFalse("Custom guide should be removed when disabled", hasCustomGuide(editor))
            assertTrue("Native guides should be restored when disabled", editor.settings.isIndentGuidesShown)

            service.enabled = true
            assertTrue("Custom guide should come back when re-enabled", hasCustomGuide(editor))
            assertFalse("Native guides should be hidden again", editor.settings.isIndentGuidesShown)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testToggleActionCyclesBothWays() {
        val editor = configureBlockEditor()
        activate(editor)
        try {
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
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testEnabledStateUsesTrueAsDefault() {
        val properties = PropertiesComponent.getInstance()
        properties.unsetValue(ENABLED_KEY)
        assertTrue(service.enabled)

        service.enabled = false
        assertEquals("false", properties.getValue(ENABLED_KEY))
        assertFalse(service.enabled)

        service.enabled = true
        assertNull(properties.getValue(ENABLED_KEY))
        assertTrue(service.enabled)
    }

    fun testActionIdIsRegisteredForIdeaVim() {
        val action = ActionManager.getInstance().getAction("IndentScope.ToggleGuide")
        assertNotNull("The action ID is part of the public IdeaVim mapping", action)
        assertTrue(action is ToggleIndentScopeGuideAction)
    }

    fun testGuideExtendsToClosingParenthesis() {
        myFixture.configureByText(
            "Example.txt",
            """
            fun main() {
                foo(
                    first,
                    second,
                )
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))
        activate(editor)
        try {
            val guide = customGuide(editor)
            assertNotNull("Custom guide should be visible", guide)
            val document = editor.document
            assertEquals(document.getLineStartOffset(1), guide!!.startOffset)
            assertEquals(document.getLineEndOffset(4), guide.endOffset)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testGuideExtendsToClosingBracket() {
        myFixture.configureByText(
            "Example.txt",
            """
            {
              "items": [
                "first",
                "second"
              ]
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 6))
        activate(editor)
        try {
            val guide = customGuide(editor)
            assertNotNull("Custom guide should be visible for a bracket scope", guide)
            val document = editor.document
            assertEquals(document.getLineStartOffset(1), guide!!.startOffset)
            assertEquals(document.getLineEndOffset(4), guide.endOffset)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testUnbracedScopeStopsAtLastBodyLine() {
        myFixture.configureByText(
            "Example.txt",
            """
            fun main() {
                if (condition)
                    doSomething()
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))
        activate(editor)
        try {
            val guide = customGuide(editor)
            assertNotNull("Custom guide should be visible for an unbraced scope", guide)
            val document = editor.document
            assertEquals(document.getLineStartOffset(1), guide!!.startOffset)
            assertEquals(document.getLineEndOffset(2), guide.endOffset)
            assertTrue(
                "The guide must not borrow the closing brace of the outer block",
                guide.endOffset < document.getLineStartOffset(3),
            )
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testToggleAppliesToAllOpenEditors() {
        val first = configureBlockEditor()
        val second = EditorFactory.getInstance().createEditor(first.document, project) as EditorEx
        second.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))
        activate(first)
        // The listener may have registered the second editor already; recreate its
        // controller so the caret position used for the first update is deterministic.
        service.editorReleased(second)
        service.editorCreated(second)
        try {
            assertTrue("First editor should show the guide", hasCustomGuide(first))
            assertTrue("Second editor should show the guide", hasCustomGuide(second))
            assertFalse(first.settings.isIndentGuidesShown)
            assertFalse(second.settings.isIndentGuidesShown)

            service.enabled = false
            assertFalse("First editor should hide the guide", hasCustomGuide(first))
            assertFalse("Second editor should hide the guide", hasCustomGuide(second))
            assertTrue("First editor should restore native guides", first.settings.isIndentGuidesShown)
            assertTrue("Second editor should restore native guides", second.settings.isIndentGuidesShown)

            service.enabled = true
            assertTrue("First editor should show the guide again", hasCustomGuide(first))
            assertTrue("Second editor should show the guide again", hasCustomGuide(second))
            assertFalse(first.settings.isIndentGuidesShown)
            assertFalse(second.settings.isIndentGuidesShown)
        } finally {
            service.editorReleased(first)
            service.editorReleased(second)
            EditorFactory.getInstance().releaseEditor(second)
        }
    }

    private fun configureBlockEditor(): EditorEx {
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
        return editor
    }

    private fun activate(editor: EditorEx) {
        PropertiesComponent.getInstance().unsetValue(ENABLED_KEY)
        // The fixture editor is registered by the plugin listener as soon as it is
        // created (with the caret on line 0), so recreate the controller once the
        // caret is inside the block to get a deterministic synchronous update.
        service.editorReleased(editor)
        service.editorCreated(editor)
    }

    private fun customGuide(editor: EditorEx): RangeHighlighter? =
        editor.markupModel.allHighlighters.firstOrNull {
            it.customRenderer?.javaClass?.name == "com.access.ActiveIndentGuideRenderer"
        }

    private fun hasCustomGuide(editor: EditorEx): Boolean = customGuide(editor) != null

    private companion object {
        private const val ENABLED_KEY = "IndentScope.enabled"
    }
}
