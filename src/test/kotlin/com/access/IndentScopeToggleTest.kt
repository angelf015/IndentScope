package com.access

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class IndentScopeToggleTest : BasePlatformTestCase() {

    fun testPluginDescriptorRequiresRestart() {
        val descriptor = javaClass.getResourceAsStream("/META-INF/plugin.xml")
        assertNotNull("Plugin descriptor should be available in tests", descriptor)
        val document = descriptor!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        assertEquals(
            "The plugin must require a restart so it activates right after installation",
            "true",
            document.documentElement.getAttribute("require-restart"),
        )
        assertEquals(
            "Settings must be registered under Tools",
            1,
            document.getElementsByTagName("applicationConfigurable").length,
        )
        assertEquals(
            "The color settings page must be registered",
            1,
            document.getElementsByTagName("colorSettingsPage").length,
        )
        assertEquals(
            "The status bar widget must be registered",
            1,
            document.getElementsByTagName("statusBarWidgetFactory").length,
        )
        assertEquals(
            "User-visible strings are loaded from the plugin bundle",
            "messages.IndentScopeBundle",
            document.getElementsByTagName("resource-bundle").item(0).textContent,
        )
    }

    fun testColumnZeroGuideHasNoArms() {
        val path = createScopePath(
            guideX = 20,
            firstTextX = 20,
            firstY = 10,
            lastTextX = 20,
            lastY = 50,
            drawArms = false,
        )
        assertEquals(
            "A guide at column 0 must be a plain vertical line without end hooks",
            0.0,
            path.bounds2D.width,
            0.0,
        )
    }

    fun testIndentedGuideKeepsRoundedArms() {
        val path = createScopePath(
            guideX = 20,
            firstTextX = 24,
            firstY = 10,
            lastTextX = 24,
            lastY = 50,
            drawArms = true,
        )
        assertEquals(
            "An indented guide must still reach the opener and closing text",
            4.0,
            path.bounds2D.width,
            0.001,
        )
    }

    fun testColumnZeroGuideRunsBelowFirstLineAndAboveLastLine() {
        val extent = guideVerticalExtent(
            firstLineTopY = 10,
            firstLineBottomY = 30,
            lastLineTopY = 50,
            lastLineBottomY = 70,
            joinLineCenters = false,
            endsAtClosingLine = true,
        )
        assertNotNull(extent)
        assertEquals(
            "A column-0 guide must start below the line that opens the scope",
            30,
            extent!!.first,
        )
        assertEquals(
            "A column-0 guide must end above the closing line",
            50,
            extent.last,
        )
    }

    fun testColumnZeroGuideCoversUnbracedLastBodyLine() {
        val extent = guideVerticalExtent(
            firstLineTopY = 10,
            firstLineBottomY = 30,
            lastLineTopY = 50,
            lastLineBottomY = 70,
            joinLineCenters = false,
            endsAtClosingLine = false,
        )
        assertNotNull(extent)
        assertEquals(
            "An unbraced scope starts below its first line",
            30,
            extent!!.first,
        )
        assertEquals(
            "An unbraced scope must cover its last body line",
            70,
            extent.last,
        )
    }

    fun testArmedGuideStillJoinsLineCenters() {
        val extent = guideVerticalExtent(
            firstLineTopY = 10,
            firstLineBottomY = 30,
            lastLineTopY = 50,
            lastLineBottomY = 70,
            joinLineCenters = true,
            endsAtClosingLine = true,
        )
        assertNotNull(extent)
        assertEquals("Armed guides keep the existing top position", 20, extent!!.first)
        assertEquals("Armed guides keep the existing bottom position", 60, extent.last)
    }

    fun testAdjacentColumnZeroLinesHaveNoGuide() {
        assertNull(
            "There is no room between adjacent opener and closing lines",
            guideVerticalExtent(
                firstLineTopY = 10,
                firstLineBottomY = 30,
                lastLineTopY = 30,
                lastLineBottomY = 50,
                joinLineCenters = false,
                endsAtClosingLine = true,
            ),
        )
    }

    private val service: IndentScopeService
        get() = ApplicationManager.getApplication().getService(IndentScopeService::class.java)

    private var originalSettings: IndentScopeSettings.State? = null

    override fun setUp() {
        super.setUp()
        originalSettings = IndentScopeSettings.getInstance().getState().copy()
        // Most integration fixtures use top-level scopes at column 0. Keep the
        // historical visibility for those tests; the opt-in default and the new
        // vertical geometry have dedicated tests below.
        IndentScopeSettings.getInstance().apply(
            IndentScopeSettings.getInstance().getState().copy(showColumnZeroGuide = true),
        )
    }

    override fun tearDown() {
        try {
            originalSettings?.let { IndentScopeSettings.getInstance().apply(it) }
        } finally {
            super.tearDown()
        }
    }

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
            val event = AnActionEvent.createEvent(
                action,
                DataContext.EMPTY_CONTEXT,
                null,
                "IndentScopeTest",
                ActionUiKind.NONE,
                null,
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

    fun testSettingsDefaultToEnabledAndPersistState() {
        val settings = IndentScopeSettings.getInstance()
        settings.apply(IndentScopeSettings.State())

        assertTrue("The guide is enabled by default", settings.enabled)
        assertFalse(
            "Guides for scopes starting at column 0 are off by default",
            settings.getState().showColumnZeroGuide,
        )
        assertEquals(IndentScopeSettings.State(), settings.getState())

        settings.enabled = false
        assertFalse(settings.enabled)
        assertFalse(
            "The exported PersistentStateComponent state must carry the toggle",
            settings.getState().enabled,
        )

        settings.enabled = true
        assertTrue(settings.getState().enabled)
    }

    fun testColumnZeroGuideIsHiddenByDefault() {
        val editor = configureTopLevelDtoEditor()
        updateSettings { showColumnZeroGuide = false }
        activate(editor)
        try {
            assertFalse(
                "A scope starting at column 0 must not draw a guide by default",
                hasCustomGuide(editor),
            )
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testColumnZeroGuideCanBeEnabled() {
        val editor = configureTopLevelDtoEditor()
        updateSettings { showColumnZeroGuide = true }
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 3)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testColumnZeroGuideFollowsSettingChanges() {
        val editor = configureTopLevelDtoEditor()
        updateSettings { showColumnZeroGuide = false }
        activate(editor)
        try {
            assertFalse("The guide starts hidden", hasCustomGuide(editor))

            updateSettings { showColumnZeroGuide = true }
            assertGuideSpansLines(editor, startLine = 0, endLine = 3)

            updateSettings { showColumnZeroGuide = false }
            assertFalse(
                "Disabling the setting must remove the guide without recreating the editor",
                hasCustomGuide(editor),
            )
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testColumnZeroUnbracedGuideCoversLastBodyLine() {
        myFixture.configureByText(
            "Example.py",
            "def greet():\n    first()\n    second()\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 6))
        updateSettings {
            showColumnZeroGuide = true
            customColorEnabled = true
            customColorRgb = 0xFF0000
        }
        activate(editor)
        try {
            val highlighter = customGuide(editor)
            assertNotNull("The column-0 Python guide should be visible", highlighter)
            val image = paintGuide(editor, highlighter!!)

            val document = editor.document
            val guideX = editor.offsetToXY(document.getLineStartOffset(0)).x
            val lastLineTop = editor.visualLineToY(
                editor.offsetToVisualLine(document.getLineStartOffset(2), false),
            )
            assertTrue(
                "The guide should cover the last body line",
                hasPaintedPixel(image, guideX, lastLineTop + editor.lineHeight - 3),
            )
            assertFalse(
                "The guide must stop at the bottom of the last body line",
                hasPaintedPixel(image, guideX, lastLineTop + editor.lineHeight + 3),
            )
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testColumnZeroGuidePaintAvoidsFirstAndLastLines() {
        val editor = configureTopLevelDtoEditor()
        updateSettings {
            showColumnZeroGuide = true
            customColorEnabled = true
            customColorRgb = 0xFF0000
        }
        activate(editor)
        try {
            val highlighter = customGuide(editor)
            assertNotNull("The column-0 guide should be visible", highlighter)
            val image = paintGuide(editor, highlighter!!)

            val document = editor.document
            val guideX = editor.offsetToXY(document.getLineStartOffset(0)).x
            val firstLineBottom = editor.visualLineToY(
                editor.offsetToVisualLine(document.getLineEndOffset(0), true),
            ) + editor.lineHeight
            val lastLineTop = editor.visualLineToY(
                editor.offsetToVisualLine(document.getLineStartOffset(3), false),
            )
            assertTrue(
                "The guide should start below the declaration line",
                hasPaintedPixel(image, guideX, firstLineBottom + 3),
            )
            assertFalse(
                "The guide must not cross the declaration line",
                hasPaintedPixel(image, guideX, firstLineBottom - 3),
            )
            assertTrue(
                "The guide should reach the space above the closing line",
                hasPaintedPixel(image, guideX, lastLineTop - 3),
            )
            assertFalse(
                "The guide must not cross the closing line",
                hasPaintedPixel(image, guideX, lastLineTop + 3),
            )
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testLegacyEnabledValueIsMigrated() {
        val properties = com.intellij.ide.util.PropertiesComponent.getInstance()
        val settings = IndentScopeSettings.getInstance()

        properties.setValue(LEGACY_ENABLED_KEY, "false")
        settings.noStateLoaded()

        assertFalse("The old PropertiesComponent toggle must be preserved", settings.enabled)
        assertNull(
            "The legacy key is removed after migration",
            properties.getValue(LEGACY_ENABLED_KEY),
        )
    }

    fun testParenthesesCanBeDisabledAndBlockGuideRemains() {
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
        updateSettings { showParenScopes = false }

        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 5)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testBlocksCanBeDisabledWhileParenthesesRemain() {
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
        updateSettings {
            showBraceScopes = false
            showParenScopes = true
        }

        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 1, endLine = 4)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testBracketsCanBeDisabledAndBlockGuideRemains() {
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
        updateSettings { showBracketScopes = false }

        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 6))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 5)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testDisablingAllScopeTypesHidesGuide() {
        val editor = configureBlockEditor()
        updateSettings {
            showBraceScopes = false
            showParenScopes = false
            showBracketScopes = false
        }
        activate(editor)
        try {
            assertNull("No scope type is enabled", customGuide(editor))
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testNativeGuidesCanBeKeptVisible() {
        val editor = configureBlockEditor()
        editor.settings.isIndentGuidesShown = true
        updateSettings { keepNativeGuides = true }
        activate(editor)
        try {
            assertTrue("The custom guide should remain available", hasCustomGuide(editor))
            assertTrue("Native guides must stay visible when requested", editor.settings.isIndentGuidesShown)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testSettingsConfigurableCanBeCreated() {
        val configurable = IndentScopeConfigurable()
        assertEquals("com.access.IndentScope.settings", configurable.id)
        assertEquals("IndentScope", configurable.displayName)

        val component = configurable.createComponent()
        assertNotNull(component)
        assertFalse("A freshly reset panel should not be modified", configurable.isModified())
        configurable.disposeUIResources()
    }

    fun testColorSettingsPageExposesGuideColor() {
        val page = IndentScopeColorSettingsPage()
        assertEquals("IndentScope", page.displayName)
        assertEquals(1, page.attributeDescriptors.size)
        assertTrue(
            "The demo text must use the guide highlighting tag",
            page.additionalHighlightingTagToDescriptorMap.containsKey("guide"),
        )
    }

    fun testStatusBarWidgetIsCreated() {
        val factory = IndentScopeStatusBarWidgetFactory()
        val widget = factory.createWidget(project)
        try {
            assertEquals(IndentScopeStatusBarWidgetFactory.WIDGET_ID, widget.ID())
            assertTrue(widget.getPresentation() is com.intellij.openapi.wm.StatusBarWidget.TextPresentation)
        } finally {
            widget.dispose()
        }
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

    fun testMultilineConstructorGuideEndsAtClosingBrace() {
        myFixture.configureByText(
            "Example.java",
            """
            class Example {
                private final Object first;

                public Example(Object first,
                               Object second,
                               Object third) {
                    this.first = first;
                    this.second = second;
                    this.third = third;
                }
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(8, 8))
        activate(editor)
        try {
            val guide = customGuide(editor)
            assertNotNull("Custom guide should be visible in the constructor body", guide)
            val document = editor.document
            assertEquals(
                "Guide should start at the constructor declaration",
                document.getLineStartOffset(3),
                guide!!.startOffset,
            )
            assertEquals(
                "Guide should end at the constructor's closing brace",
                document.getLineEndOffset(9),
                guide.endOffset,
            )
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testMultilineSignatureWithAlignedClosingParenthesis() {
        myFixture.configureByText(
            "Example.java",
            """
            class Example {
                public Example(
                    Object first,
                    Object second
                ) {
                    this.first = first;
                }
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(5, 8))
        activate(editor)
        try {
            val guide = customGuide(editor)
            assertNotNull("Custom guide should be visible in the constructor body", guide)
            val document = editor.document
            assertEquals(
                "Guide should start at the constructor declaration, not at `) {`",
                document.getLineStartOffset(1),
                guide!!.startOffset,
            )
            assertEquals(
                "Guide should end at the constructor's closing brace",
                document.getLineEndOffset(6),
                guide.endOffset,
            )
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testTopLevelClassGuideCoversFields() {
        myFixture.configureByText(
            "Example.java",
            """
            @Data
            public class Example {
                private String first;
                private String second;
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(3, 8))
        activate(editor)
        try {
            val guide = customGuide(editor)
            assertNotNull("A DTO with only fields should still show the class guide", guide)
            val document = editor.document
            assertEquals(
                "Guide should start at the class declaration, below the annotation",
                document.getLineStartOffset(1),
                guide!!.startOffset,
            )
            assertEquals(
                "Guide should end at the class's closing brace",
                document.getLineEndOffset(4),
                guide.endOffset,
            )
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

    fun testJavaScopeIgnoresBracesInStringsAndComments() {
        myFixture.configureByText(
            "Example.java",
            """
            class Example {
                String opener = "{";
                // } a line comment
                void run() {
                    String closer = "}";
                    /* } a block comment */
                    int value = 1;
                }
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(6, 8))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 3, endLine = 7)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testKotlinScopeIgnoresBracesInTripleQuotedString() {
        myFixture.configureByText(
            "Example.kt",
            "fun main() {\n" +
                "    val text = \"\"\"\n" +
                "        }\n" +
                "    \"\"\".trimIndent()\n" +
                "    println(text)\n" +
                "}\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(4, 4))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 5)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testKotlinScopeIgnoresBracesInNestedComments() {
        myFixture.configureByText(
            "Example.kt",
            """
            fun main() {
                /* outer
                   /* inner } */
                   still comment
                */
                println("done")
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(5, 4))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 6)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testFallbackIgnoresBracesInStringsAndComments() {
        myFixture.configureByText(
            "Example.txt",
            """
            fun main() {
                val text = "}"
                // } a line comment
                val other = '{'
                doWork()
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(4, 4))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 5)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testFallbackIgnoresBracesInBacktickStrings() {
        myFixture.configureByText(
            "Example.js",
            """
            function main() {
              const text = `
                }
              `;
              console.log(text);
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(4, 2))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 5)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testFallbackIgnoresBlockCommentOnOpenerLine() {
        myFixture.configureByText(
            "Example.txt",
            "fun main() { /* } */\n    doWork()\n}\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(1, 4))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 2)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testFallbackIgnoresBacktickOnOpenerLine() {
        myFixture.configureByText(
            "Example.txt",
            "fun main() { val text = `}`\n    doWork()\n}\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(1, 4))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 2)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testAllmanMethodGuideStartsAtDeclaration() {
        myFixture.configureByText(
            "Example.java",
            """
            public class Example
            {
                void run()
                {
                    int value = 1;
                }
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(4, 8))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 2, endLine = 5)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testAllmanClassGuideStartsAtDeclaration() {
        myFixture.configureByText(
            "Example.java",
            """
            public class Example
            {
                private String name;
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 3)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testAllmanMultilineSignatureStartsAtDeclaration() {
        myFixture.configureByText(
            "Example.java",
            """
            public class Example
            {
                void run(
                    int value
                )
                {
                    int result = value;
                }
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(6, 8))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 2, endLine = 7)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testFallbackAllmanGuideStartsAtDeclaration() {
        myFixture.configureByText(
            "Example.txt",
            "fun main()\n{\n    doWork()\n}\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 4))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 3)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testKotlinAllmanFunctionGuideStartsAtDeclaration() {
        myFixture.configureByText(
            "Example.kt",
            "fun main()\n{\n    println(\"hi\")\n}\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 4))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 3)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testTopLevelPythonDefShowsGuide() {
        myFixture.configureByText(
            "Example.py",
            "def greet():\n    print(\"hi\")\n    return 1\n\ngreet()\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(1, 4))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 2)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testSingleLineBlockHasNoGuide() {
        myFixture.configureByText(
            "Example.txt",
            "fun main() { println(\"hi\") }\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(0, 8))
        activate(editor)
        try {
            assertNull(
                "A block that opens and closes on the same line has no vertical guide",
                customGuide(editor),
            )
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testMultilineEmptyBlockShowsGuide() {
        myFixture.configureByText(
            "Example.txt",
            "fun main() {\n}\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(0, 4))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 1)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testBlockWithBlankBodyShowsGuide() {
        myFixture.configureByText(
            "Example.txt",
            "fun main() {\n\n}\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(1, 0))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 0, endLine = 2)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testMixedIndentationScope() {
        myFixture.configureByText(
            "Example.txt",
            "fun main() {\n    if (true) {\n\t    doWork()\n\t}\n}\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 5))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 1, endLine = 3)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testTabIndentationIsMeasuredInVisualColumns() {
        myFixture.configureByText(
            "Example.txt",
            "fun main() {\n\tif (true) {\n\t\tdoWork()\n\t}\n}\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 2))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 1, endLine = 3)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testCrLfDocumentDoesNotBreakScopeDetection() {
        myFixture.configureByText(
            "Example.txt",
            "fun main() {\r\n    if (true) {\r\n        doWork()\r\n    }\r\n}\r\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 1, endLine = 3)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testTextScannerIgnoresVerbatimStringBraces() {
        val document = EditorFactory.getInstance().createDocument(
            "class C\n{\n    string value = @\"}\";\n}\n",
        )
        val chars = document.charsSequence
        val scanner = TextBracketScanner(
            TextLineMap(chars),
            chars,
            TextSyntax.forFileName("Example.cs"),
        )
        val opener = scanner.lastUnmatchedOpenerOnLine(1)
        assertNotNull("Class brace should be found", opener)
        assertEquals('{', opener!!.char)
        assertEquals(
            "A brace inside a C# verbatim string must not close the class",
            document.getLineStartOffset(3),
            scanner.matchingCloser(opener),
        )
    }

    fun testTextScannerIgnoresNestedBlockCommentBraces() {
        val document = EditorFactory.getInstance().createDocument(
            "fun main() {\n    /* outer\n       /* inner } */\n       still comment\n    */\n}\n",
        )
        val chars = document.charsSequence
        val scanner = TextBracketScanner(
            TextLineMap(chars),
            chars,
            TextSyntax.forFileName("Example.kt"),
        )
        val opener = scanner.lastUnmatchedOpenerOnLine(0)
        assertNotNull("Function brace should be found", opener)
        assertEquals(
            "A brace inside a nested block comment must not close the function",
            document.getLineStartOffset(5),
            scanner.matchingCloser(opener!!),
        )
    }

    fun testTextScannerIgnoresBraceOnLineInsideMultilineString() {
        val document = EditorFactory.getInstance().createDocument(
            "fun main() {\n    val text = \"\"\"\n    {\n        content\n    }\n    \"\"\"\n}\n",
        )
        val chars = document.charsSequence
        val scanner = TextBracketScanner(
            TextLineMap(chars),
            chars,
            TextSyntax.forFileName("Example.kt"),
        )
        assertNull(
            "A brace inside a triple-quoted string is not an opener for that line",
            scanner.lastUnmatchedOpenerOnLine(2),
        )
    }

    fun testTextScannerHandlesKotlinRawStringWithBackslashBeforeDelimiter() {
        val document = EditorFactory.getInstance().createDocument(
            "fun main() {\n    val text = \"\"\"value\\\"\"\"\n    doWork()\n}\n",
        )
        val chars = document.charsSequence
        val scanner = TextBracketScanner(
            TextLineMap(chars),
            chars,
            TextSyntax.forFileName("Example.kt"),
        )
        val opener = scanner.lastUnmatchedOpenerOnLine(0)
        assertNotNull("Function brace should be found", opener)
        assertEquals(
            "A backslash inside a Kotlin raw string must not escape the closing delimiter",
            document.getLineStartOffset(3),
            scanner.matchingCloser(opener!!),
        )
    }

    fun testRegisterOpenEditorsCoversExistingEditors() {
        val editor = configureBlockEditor()
        service.editorReleased(editor)
        try {
            assertFalse("Editor should start without a controller", hasCustomGuide(editor))

            service.registerOpenEditors()

            assertTrue("Registering open editors should install the guide", hasCustomGuide(editor))
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

    fun testCollapsedScopeHidesGuideUntilExpanded() {
        val editor = configureBlockEditor()
        activate(editor)
        try {
            val region = foldLines(editor, startLine = 0, endLine = 4)
            assertFalse("The fold region should be collapsed", region.isExpanded)
            assertEquals(1, editor.foldingModel.allFoldRegions.size)
            PlatformTestUtil.waitForAlarm(30)

            assertNull(
                "A guide whose opening and closing lines are collapsed has no visible extent",
                customGuide(editor),
            )

            editor.foldingModel.runBatchFoldingOperation { region.isExpanded = true }
            PlatformTestUtil.waitForAlarm(30)

            // Expanding restores the caret to the nested block it came from.
            assertGuideSpansLines(editor, startLine = 1, endLine = 3)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testFoldInsideScopeKeepsGuide() {
        myFixture.configureByText(
            "Example.txt",
            """
            fun main() {
                val a = 1
                if (true) {
                    doWork()
                }
                val b = 2
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(5, 8))
        activate(editor)
        try {
            foldLines(editor, startLine = 2, endLine = 3)
            PlatformTestUtil.waitForAlarm(30)

            assertGuideSpansLines(editor, startLine = 0, endLine = 6)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testPrimaryCaretDrivesGuideWithMultipleCarets() {
        myFixture.configureByText(
            "Example.txt",
            """
            class Example {
                fun run() {
                    work()
                }
                fun other() {
                    otherWork()
                }
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))
        activate(editor)
        try {
            assertGuideSpansLines(editor, startLine = 1, endLine = 3)

            val secondary = editor.caretModel.addCaret(LogicalPosition(5, 8), false)
            assertNotNull("The fixture editor should support multiple carets", secondary)
            PlatformTestUtil.waitForAlarm(30)

            assertGuideSpansLines(
                editor,
                startLine = 1,
                endLine = 3,
            )

            val newPrimary = editor.caretModel.addCaret(LogicalPosition(5, 12), true)
            assertNotNull(newPrimary)
            PlatformTestUtil.waitForAlarm(30)

            assertGuideSpansLines(editor, startLine = 4, endLine = 6)
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testPreviewEditorIsFilteredByDefaultAndCanBeEnabled() {
        val document = EditorFactory.getInstance().createDocument(
            "fun main() {\n    if (true) {\n        doWork()\n    }\n}\n",
        )
        val editor = EditorFactory.getInstance()
            .createEditor(document, project, EditorKind.PREVIEW) as EditorEx
        editor.settings.isIndentGuidesShown = true
        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))
        service.editorReleased(editor)

        try {
            updateSettings {
                showBraceScopes = true
                showInPreviewEditors = false
            }
            service.editorCreated(editor)

            assertNull(
                "Preview editors are ignored unless explicitly enabled",
                customGuide(editor),
            )
            assertTrue("Native guides must not be hidden in ignored editors", editor.settings.isIndentGuidesShown)

            updateSettings { showInPreviewEditors = true }

            assertGuideSpansLines(editor, startLine = 1, endLine = 3)
            assertFalse("The enabled preview editor follows the native-guide policy", editor.settings.isIndentGuidesShown)
        } finally {
            service.editorReleased(editor)
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testExternalShowIndentGuidesChangeReappliesPolicy() {
        val editor = configureBlockEditor()
        val externalSettings = EditorSettingsExternalizable.getInstance()
        val originalExternalValue = externalSettings.isIndentGuidesShown
        activate(editor)
        try {
            assertFalse(editor.settings.isIndentGuidesShown)

            externalSettings.isIndentGuidesShown = true
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertFalse(
                "The plugin's native-guide policy must survive an external Show indent guides change",
                editor.settings.isIndentGuidesShown,
            )

            service.enabled = false
            assertTrue(
                "Disabling the plugin restores the user's latest external preference",
                editor.settings.isIndentGuidesShown,
            )
        } finally {
            externalSettings.isIndentGuidesShown = originalExternalValue
            service.editorReleased(editor)
        }
    }

    fun testHighlighterIsReusedAndRendererRefreshedWhenSettingsChange() {
        val editor = configureBlockEditor()
        activate(editor)
        try {
            val highlighter = customGuide(editor)
            assertNotNull("Custom guide should be visible", highlighter)
            val initialRenderer = highlighter!!.customRenderer as ActiveIndentGuideRenderer
            assertNull("No custom color is configured initially", initialRenderer.customColor)

            updateSettings {
                customColorEnabled = true
                customColorRgb = 0xFF0000
                strokeWidth = 3.0
            }

            val updated = customGuide(editor)
            assertSame(
                "The same scope should keep and reuse its RangeHighlighter",
                highlighter,
                updated,
            )
            val updatedRenderer = updated!!.customRenderer as ActiveIndentGuideRenderer
            assertEquals(
                "Changing settings must refresh the renderer on the reused highlighter",
                java.awt.Color(0xFF0000),
                updatedRenderer.customColor,
            )
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testReleasedEditorIsRemovedFromService() {
        myFixture.configureByText(
            "Example.txt",
            "fun main() {\n    work()\n}\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(1, 4))
        service.editorCreated(editor)
        val before = service.registeredControllerCount()
        assertTrue("The fixture editor should have a controller", before > 0)

        service.editorReleased(editor)

        assertEquals(
            "Releasing an editor must remove its controller from the service map",
            before - 1,
            service.registeredControllerCount(),
        )
    }

    fun testControllerDisposeRestoresNativeGuides() {
        val editor = configureBlockEditor()
        // Detach the application-level controller so this test owns the editor lifecycle.
        service.editorReleased(editor)
        editor.settings.isIndentGuidesShown = true

        val controller = CurrentScopeIndentGuideController(editor)
        try {
            controller.onSettingsChanged()
            assertTrue("The controller should paint the custom guide", hasCustomGuide(editor))
            assertFalse(
                "The controller should hide native guides while active",
                editor.settings.isIndentGuidesShown,
            )

            controller.dispose()

            assertFalse("Disposing the controller must remove its guide", hasCustomGuide(editor))
            assertTrue(
                "Disposing the controller must restore native guides",
                editor.settings.isIndentGuidesShown,
            )
        } finally {
            controller.dispose()
            service.editorReleased(editor)
        }
    }

    fun testServiceDisposeRemovesAllControllers() {
        val editor = configureBlockEditor()
        service.editorReleased(editor)

        val localService = IndentScopeService()
        try {
            localService.editorCreated(editor)
            assertTrue("The service should register the editor", localService.hasController(editor))
            assertEquals(1, localService.registeredControllerCount())

            localService.dispose()

            assertEquals(
                "Disposing the service must clear its controller map",
                0,
                localService.registeredControllerCount(),
            )
            assertFalse("A disposed service must not keep the editor", localService.hasController(editor))
        } finally {
            localService.dispose()
            service.editorReleased(editor)
        }
    }

    fun testBackgroundEditorRegistrationIsDispatchedToEdt() {
        val editor = configureBlockEditor()
        service.editorReleased(editor)
        val registered = CountDownLatch(1)
        try {
            ApplicationManager.getApplication().executeOnPooledThread {
                service.editorCreated(editor)
                registered.countDown()
            }
            assertTrue(
                "Background registration should finish",
                registered.await(5, TimeUnit.SECONDS),
            )

            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertTrue(
                "Background registration must be applied on the EDT",
                service.hasController(editor),
            )
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testConcurrentEditorRegistrationCreatesSingleController() {
        val editor = configureBlockEditor()
        service.editorReleased(editor)
        val threads = 4
        val done = CountDownLatch(threads)
        try {
            repeat(threads) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    service.editorCreated(editor)
                    done.countDown()
                }
            }
            assertTrue(
                "Concurrent registrations should finish",
                done.await(5, TimeUnit.SECONDS),
            )

            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            assertTrue("The editor must be registered", service.hasController(editor))
            assertEquals(
                "Concurrent registration must not create duplicate guides",
                1,
                editor.markupModel.allHighlighters.count { it.customRenderer is ActiveIndentGuideRenderer },
            )
        } finally {
            service.editorReleased(editor)
        }
    }

    fun testSoftWrappedScopeStillPaints() {
        myFixture.configureByText(
            "Example.txt",
            "class Example {\n" +
                "    fun main() {\n" +
                "        val text = \"a very long line that should wrap inside the test editor\"\n" +
                "        doWork()\n" +
                "    }\n" +
                "}\n",
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(3, 8))
        activate(editor)
        try {
            assertTrue(
                "The test editor should actually soft-wrap the long line",
                EditorTestUtil.configureSoftWraps(editor, 20),
            )
            assertFalse(
                "Line 2 should be split into several visual lines",
                editor.softWrapModel.getSoftWrapsForLine(2).isEmpty(),
            )
            PlatformTestUtil.waitForAlarm(30)
            assertGuideSpansLines(editor, startLine = 1, endLine = 4)

            val highlighter = customGuide(editor)
            assertNotNull(highlighter)
            val image = BufferedImage(180, 300, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                highlighter!!.customRenderer?.paint(editor, highlighter, graphics)
            } finally {
                graphics.dispose()
            }
        } finally {
            service.editorReleased(editor)
        }
    }

    private fun foldLines(editor: EditorEx, startLine: Int, endLine: Int): com.intellij.openapi.editor.FoldRegion {
        val document = editor.document
        var region: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperation {
            region = editor.foldingModel.addFoldRegion(
                document.getLineStartOffset(startLine),
                document.getLineEndOffset(endLine),
                "...",
            )
            region?.isExpanded = false
        }
        assertNotNull("The editor should create the fold region", region)
        return region!!
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

    private fun configureTopLevelDtoEditor(): EditorEx {
        myFixture.configureByText(
            "UserDto.java",
            """
            public class UserDto {
                private final String name;
                private final int age;
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor as EditorEx
        editor.caretModel.moveToLogicalPosition(LogicalPosition(1, 8))
        return editor
    }

    private fun activate(editor: EditorEx) {
        IndentScopeSettings.getInstance().enabled = true
        // The fixture editor is registered by the plugin listener as soon as it is
        // created (with the caret on line 0), so recreate the controller once the
        // caret is inside the block to get a deterministic synchronous update.
        service.editorReleased(editor)
        service.editorCreated(editor)
    }

    private fun updateSettings(block: IndentScopeSettings.State.() -> Unit) {
        val settings = IndentScopeSettings.getInstance()
        settings.apply(settings.getState().copy().apply(block))
    }

    private fun customGuide(editor: EditorEx): RangeHighlighter? = editor.markupModel.allHighlighters.firstOrNull {
        it.customRenderer?.javaClass?.name == "com.access.ActiveIndentGuideRenderer"
    }

    private fun assertGuideSpansLines(editor: EditorEx, startLine: Int, endLine: Int) {
        val guide = customGuide(editor)
        assertNotNull("Custom guide should be visible", guide)
        val document = editor.document
        assertEquals(
            "Guide should start on line $startLine",
            document.getLineStartOffset(startLine),
            guide!!.startOffset,
        )
        assertEquals(
            "Guide should end on line $endLine",
            document.getLineEndOffset(endLine),
            guide.endOffset,
        )
    }

    private fun hasCustomGuide(editor: EditorEx): Boolean = customGuide(editor) != null

    private fun paintGuide(editor: EditorEx, highlighter: RangeHighlighter): BufferedImage {
        val image = BufferedImage(300, 400, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            highlighter.customRenderer!!.paint(editor, highlighter, graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun hasPaintedPixel(image: BufferedImage, x: Int, y: Int): Boolean {
        for (dx in -2..2) {
            for (dy in -2..2) {
                val px = x + dx
                val py = y + dy
                if (px !in 0 until image.width || py !in 0 until image.height) continue
                if (image.getRGB(px, py) ushr 24 != 0) return true
            }
        }
        return false
    }

    private companion object {
        private const val LEGACY_ENABLED_KEY = "IndentScope.enabled"
    }
}
