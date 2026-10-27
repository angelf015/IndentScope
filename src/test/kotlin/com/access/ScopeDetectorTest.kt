package com.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

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

    private fun detect(
        text: String,
        caretLine: Int,
        fileName: String = "Example.txt",
        tabSize: Int = 4,
    ): ScopeGuide? {
        val lines = TextLineMap(text)
        val brackets = TextBracketScanner(lines, text, TextSyntax.forFileName(fileName))
        return ScopeDetector(text, lines, brackets, tabSize).findGuide(caretLine)
    }
}
