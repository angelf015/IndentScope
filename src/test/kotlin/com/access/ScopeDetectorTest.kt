package com.access

import com.intellij.openapi.editor.EditorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.geom.PathIterator
import javax.swing.JPanel

class ScopeDetectorTest {

    @Test
    fun findsTopLevelUnbracedScope() {
        val guide = detect(
            text = "def greet():\n    print(\"hi\")\n    return 1\n\ngreet()\n",
            caretLine = 1,
            fileName = "Example.py",
        )

        assertNotNull("A top-level Python function is a real scope", guide)
        assertEquals(0, guide!!.anchorLine)
        assertEquals(2, guide.endLine)
    }

    @Test
    fun findsNestedUnbracedScope() {
        val guide = detect(
            text = "def greet():\n    if ready:\n        print(\"hi\")\n    return 1\n",
            caretLine = 2,
            fileName = "Example.py",
        )

        assertNotNull(guide)
        assertEquals(1, guide!!.anchorLine)
        assertEquals(2, guide.endLine)
    }

    @Test
    fun singleLineBlockHasNoGuide() {
        val guide = detect(
            text = "fun main() { println(\"hi\") }\n",
            caretLine = 0,
        )

        assertNull("A block without vertical extent should not draw a guide", guide)
    }

    @Test
    fun multilineEmptyBlockHasGuide() {
        val guide = detect(
            text = "fun main() {\n}\n",
            caretLine = 0,
        )

        assertNotNull(guide)
        assertEquals(0, guide!!.anchorLine)
        assertEquals(1, guide.endLine)
    }

    @Test
    fun blockWithBlankBodyHasGuide() {
        val guide = detect(
            text = "fun main() {\n\n}\n",
            caretLine = 1,
        )

        assertNotNull(guide)
        assertEquals(0, guide!!.anchorLine)
        assertEquals(2, guide.endLine)
    }

    @Test
    fun ignoresBracketInString() {
        val guide = detect(
            text = "fun main() {\n    val text = \"}\"\n    doWork()\n}\n",
            caretLine = 2,
        )

        assertNotNull(guide)
        assertEquals(0, guide!!.anchorLine)
        assertEquals(3, guide.endLine)
    }

    @Test
    fun ignoresBlockCommentOnOpenerLine() {
        val guide = detect(
            text = "fun main() { /* } */\n    doWork()\n}\n",
            caretLine = 1,
        )

        assertNotNull(guide)
        assertEquals(0, guide!!.anchorLine)
        assertEquals(2, guide.endLine)
    }

    @Test
    fun anchorsAllmanBlockAtDeclaration() {
        val guide = detect(
            text = "fun main()\n{\n    doWork()\n}\n",
            caretLine = 2,
        )

        assertNotNull(guide)
        assertEquals(0, guide!!.anchorLine)
        assertEquals(3, guide.endLine)
    }

    @Test
    fun supportsMixedIndentation() {
        val guide = detect(
            text = "fun main() {\n    if (true) {\n\t    doWork()\n\t}\n}\n",
            caretLine = 2,
            tabSize = 4,
        )

        assertNotNull(guide)
        assertEquals(1, guide!!.anchorLine)
        assertEquals(3, guide.endLine)
    }

    @Test
    fun supportsCrLfDocuments() {
        val guide = detect(
            text = "fun main() {\r\n    if (true) {\r\n        doWork()\r\n    }\r\n}\r\n",
            caretLine = 2,
        )

        assertNotNull(guide)
        assertEquals(1, guide!!.anchorLine)
        assertEquals(3, guide.endLine)
    }

    @Test
    fun disabledParenthesesFallBackToEnclosingBlock() {
        val guide = detect(
            text = "fun main() {\n    foo(\n        first,\n        second,\n    )\n}\n",
            caretLine = 2,
            allowedOpeners = setOf('{'),
        )

        assertNotNull("The enclosing block is still an enabled scope", guide)
        assertEquals(0, guide!!.anchorLine)
        assertEquals(5, guide.endLine)
    }

    @Test
    fun disabledBracketsFallBackToEnclosingBlock() {
        val guide = detect(
            text = "{\n  \"items\": [\n    \"first\",\n    \"second\"\n  ]\n}\n",
            caretLine = 2,
            allowedOpeners = setOf('{'),
        )

        assertNotNull(guide)
        assertEquals(0, guide!!.anchorLine)
        assertEquals(5, guide.endLine)
    }

    @Test
    fun disabledBlocksStillShowParenthesisScope() {
        val guide = detect(
            text = "fun main() {\n    foo(\n        first,\n        second,\n    )\n}\n",
            caretLine = 2,
            allowedOpeners = setOf('('),
        )

        assertNotNull(guide)
        assertEquals(1, guide!!.anchorLine)
        assertEquals(4, guide.endLine)
    }

    @Test
    fun noEnabledScopeTypesHasNoGuide() {
        val guide = detect(
            text = "fun main() {\n    foo(\n        first,\n        second,\n    )\n}\n",
            caretLine = 2,
            allowedOpeners = emptySet(),
        )

        assertNull(guide)
    }

    @Test
    fun activeEditorVisibilityRules() {
        val editor = JPanel()
        val child = JPanel()
        editor.add(child)

        assertTrue("All editors show the guide when the filter is off", shouldShowGuide(true, false, null, editor))
        assertFalse("Disabled guide is never visible", shouldShowGuide(false, false, child, editor))
        assertFalse("Without focus no active editor exists", shouldShowGuide(true, true, null, editor))
        assertTrue("A focused child belongs to the editor", shouldShowGuide(true, true, child, editor))
    }

    @Test
    fun foldedBodyKeepsGuide() {
        val guide = ScopeGuide(anchorLine = 0, endLine = 8)

        assertEquals(
            guide,
            guide.visibleWithFolds(listOf(FoldedLineRange(startLine = 2, endLine = 4))),
        )
    }

    @Test
    fun foldedOpenerHidesGuide() {
        val guide = ScopeGuide(anchorLine = 2, endLine = 8)

        assertNull(guide.visibleWithFolds(listOf(FoldedLineRange(startLine = 0, endLine = 3))))
    }

    @Test
    fun foldedClosingLineHidesGuide() {
        val guide = ScopeGuide(anchorLine = 0, endLine = 8)

        assertNull(guide.visibleWithFolds(listOf(FoldedLineRange(startLine = 6, endLine = 9))))
    }

    @Test
    fun foldStartingOnClosingLineKeepsGuide() {
        val guide = ScopeGuide(anchorLine = 0, endLine = 8)

        assertEquals(
            "The placeholder line remains visible, so the closing line still has an anchor",
            guide,
            guide.visibleWithFolds(listOf(FoldedLineRange(startLine = 8, endLine = 12))),
        )
    }

    @Test
    fun auxiliaryEditorsAreOptIn() {
        assertTrue(isEditorKindEnabled(EditorKind.MAIN_EDITOR, false, false, false))
        assertTrue(isEditorKindEnabled(EditorKind.UNTYPED, false, false, false))
        assertFalse(isEditorKindEnabled(EditorKind.CONSOLE, false, false, false))
        assertFalse(isEditorKindEnabled(EditorKind.DIFF, false, false, false))
        assertFalse(isEditorKindEnabled(EditorKind.PREVIEW, false, false, false))

        assertTrue(isEditorKindEnabled(EditorKind.CONSOLE, true, false, false))
        assertTrue(isEditorKindEnabled(EditorKind.DIFF, false, true, false))
        assertTrue(isEditorKindEnabled(EditorKind.PREVIEW, false, false, true))
    }

    @Test
    fun softWrapCrossoverOnlyCoversRowsLeftOfGuide() {
        val ranges = softWrapBreakRanges(
            guideX = 20f,
            wraps = listOf(
                SoftWrapCrossover(topY = 100, lineHeight = 16, indentPixels = 8),
                SoftWrapCrossover(topY = 116, lineHeight = 16, indentPixels = 20),
                SoftWrapCrossover(topY = 132, lineHeight = 16, indentPixels = 4),
            ),
        )

        assertEquals(listOf(100 until 116, 132 until 148), ranges)
    }

    @Test
    fun scopePathSplitsAroundSoftWrapRows() {
        val path = createScopePath(
            guideX = 20,
            firstTextX = 24,
            firstY = 10,
            lastTextX = 24,
            lastY = 100,
            drawArms = true,
            hiddenYRanges = listOf(40..55),
        )

        assertEquals("The path should have a gap for the wrapped text", 2, moveToCount(path))
        assertEquals("The arms must stay at the scope boundaries", 10.0, path.bounds2D.minY, 0.001)
        assertEquals(100.0, path.bounds2D.maxY, 0.001)
    }

    @Test
    fun scopePathKeepsArmsWhenBreakTouchesBoundary() {
        val path = createScopePath(
            guideX = 20,
            firstTextX = 24,
            firstY = 10,
            lastTextX = 24,
            lastY = 100,
            drawArms = true,
            hiddenYRanges = listOf(0..10, 100..120),
        )

        assertEquals(1, moveToCount(path))
        assertEquals(10.0, path.bounds2D.minY, 0.001)
        assertEquals(100.0, path.bounds2D.maxY, 0.001)
    }

    private fun moveToCount(path: java.awt.geom.Path2D): Int {
        val iterator = path.getPathIterator(null)
        val coordinates = DoubleArray(6)
        var count = 0
        while (!iterator.isDone) {
            if (iterator.currentSegment(coordinates) == PathIterator.SEG_MOVETO) count++
            iterator.next()
        }
        return count
    }

    private fun detect(
        text: String,
        caretLine: Int,
        fileName: String = "Example.txt",
        tabSize: Int = 4,
        allowedOpeners: Set<Char> = setOf('{', '(', '['),
    ): ScopeGuide? {
        val lines = TextLineMap(text)
        val brackets = TextBracketScanner(lines, text, TextSyntax.forFileName(fileName))
        return ScopeDetector(text, lines, brackets, tabSize, allowedOpeners).findGuide(caretLine)
    }
}
