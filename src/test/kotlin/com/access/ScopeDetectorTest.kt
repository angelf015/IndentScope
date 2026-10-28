package com.access

import com.intellij.openapi.editor.EditorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
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
        assertFalse("An unbraced scope ends at its last body line", guide.endsAtClosingLine)
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
    fun topLevelGuideIsMarkedAtColumnZero() {
        val guide = detect(
            text = "public class UserDto {\n    private String name;\n    private int age;\n}\n",
            caretLine = 1,
        )

        assertNotNull(guide)
        assertTrue("A top-level type starts at column 0", guide!!.columnZero)
        assertTrue("The guide ends at the class's closing brace", guide.endsAtClosingLine)
    }

    @Test
    fun nestedGuideIsNotMarkedAtColumnZero() {
        val guide = detect(
            text = "public class Outer {\n    public class Inner {\n        int value;\n    }\n}\n",
            caretLine = 2,
        )

        assertNotNull(guide)
        assertEquals(1, guide!!.anchorLine)
        assertFalse("An indented nested type is not at column 0", guide.columnZero)
        assertTrue("The guide ends at the nested type's closing brace", guide.endsAtClosingLine)
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
    fun anchorsContinuationLineWithBraceAtDeclaration() {
        val guide = detect(
            text = "class TicketService {\n" +
                "    public TicketService(FlyRepository flyRepository, CustomerRepository customerRepository,\n" +
                "                         TicketRepository ticketRepository) {\n" +
                "        this.ticketRepository = ticketRepository;\n" +
                "    }\n" +
                "}\n",
            caretLine = 2,
            fileName = "TicketService.java",
        )

        assertNotNull("The constructor body is a valid scope", guide)
        assertEquals(
            "The guide must start on the declaration line, not on its continuation",
            1,
            guide!!.anchorLine,
        )
        assertEquals(4, guide.endLine)
    }

    @Test
    fun anchorsContinuationLineWithThrowsClauseAtDeclaration() {
        val guide = detect(
            text = "class TicketService {\n" +
                "    public TicketService(\n" +
                "        TicketRepository ticketRepository) throws IllegalArgumentException {\n" +
                "        this.ticketRepository = ticketRepository;\n" +
                "    }\n" +
                "}\n",
            caretLine = 2,
            fileName = "TicketService.java",
        )

        assertNotNull("The constructor body is a valid scope", guide)
        assertEquals(
            "A declaration suffix after the closing parenthesis must not move the anchor",
            1,
            guide!!.anchorLine,
        )
        assertEquals(4, guide.endLine)
    }

    @Test
    fun keepsChainedLambdaAnchoredOnItsOwnLine() {
        val guide = detect(
            text = "fun main() {\n" +
                "    val x = listOf(\n" +
                "        1,\n" +
                "        2).map {\n" +
                "        it + 1\n" +
                "    }\n" +
                "}\n",
            caretLine = 3,
        )

        assertNotNull("The lambda body is a valid scope", guide)
        assertEquals(
            "A member-access chain starts a new construct on the current line",
            3,
            guide!!.anchorLine,
        )
        assertEquals(5, guide.endLine)
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
    fun findsEnclosingScopesFromInnermost() {
        val guides = detectGuides(
            text = "class Example {\n" +
                "    fun main() {\n" +
                "        if (true) {\n" +
                "            work()\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
            caretLine = 3,
            limit = 10,
        )

        assertEquals("Every enclosing scope should be returned", 3, guides.size)
        assertEquals(2, guides[0].anchorLine)
        assertEquals(1, guides[1].anchorLine)
        assertEquals(0, guides[2].anchorLine)
        assertEquals(4, guides[0].endLine)
        assertEquals(5, guides[1].endLine)
        assertEquals(6, guides[2].endLine)
    }

    @Test
    fun limitsEnclosingScopes() {
        val guides = detectGuides(
            text = "class Example {\n" +
                "    fun main() {\n" +
                "        if (true) {\n" +
                "            work()\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
            caretLine = 3,
            limit = 2,
        )

        assertEquals(2, guides.size)
        assertEquals(2, guides[0].anchorLine)
        assertEquals(1, guides[1].anchorLine)
    }

    @Test
    fun disabledScopeTypesAreSkippedWhileWalkingOutward() {
        val guides = detectGuides(
            text = "class Example {\n" +
                "    fun main() {\n" +
                "        foo(\n" +
                "            first,\n" +
                "            second,\n" +
                "        )\n" +
                "    }\n" +
                "}\n",
            caretLine = 3,
            limit = 10,
            allowedOpeners = setOf('{'),
        )

        assertEquals("The disabled parenthesis must not appear", 2, guides.size)
        assertEquals(1, guides[0].anchorLine)
        assertEquals(0, guides[1].anchorLine)
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

    @Test
    fun enclosingScopesFadeWithDepth() {
        assertEquals(1f, scopeGuideAlpha(0))
        assertEquals(0.75f, scopeGuideAlpha(1))
        assertEquals(0.5f, scopeGuideAlpha(2))
        assertEquals(0.25f, scopeGuideAlpha(3))
        assertEquals("The outermost guides must remain visible", 0.25f, scopeGuideAlpha(20))
    }

    @Test
    fun guideAlphaPreservesRgbAndScalesExistingAlpha() {
        val color = Color(10, 20, 30, 128)
        val faded = applyGuideAlpha(color, 0.5f)

        assertEquals(10, faded.red)
        assertEquals(20, faded.green)
        assertEquals(30, faded.blue)
        assertEquals(64, faded.alpha)
        assertSame("A fully opaque guide keeps the original color", color, applyGuideAlpha(color, 1f))
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

    private fun detectGuides(
        text: String,
        caretLine: Int,
        limit: Int,
        fileName: String = "Example.txt",
        tabSize: Int = 4,
        allowedOpeners: Set<Char> = setOf('{', '(', '['),
    ): List<ScopeGuide> {
        val lines = TextLineMap(text)
        val brackets = TextBracketScanner(lines, text, TextSyntax.forFileName(fileName))
        return ScopeDetector(text, lines, brackets, tabSize, allowedOpeners).findGuides(caretLine, limit)
    }
}
