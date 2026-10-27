package com.access

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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

    private val service: IndentScopeService
        get() = ApplicationManager.getApplication().getService(IndentScopeService::class.java)

    private var originalSettings: IndentScopeSettings.State? = null

    override fun setUp() {
        super.setUp()
        originalSettings = IndentScopeSettings.getInstance().getState().copy()
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

    private fun customGuide(editor: EditorEx): RangeHighlighter? =
        editor.markupModel.allHighlighters.firstOrNull {
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

    private companion object {
        private const val LEGACY_ENABLED_KEY = "IndentScope.enabled"
    }
}
