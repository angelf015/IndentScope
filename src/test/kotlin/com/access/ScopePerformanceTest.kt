package com.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the document snapshot caches introduced in step 4.
 * The assertions intentionally avoid fragile micro-benchmarks: they verify that
 * repeated queries reuse the index and that very large files stay within a
 * generous time budget.
 */
class ScopePerformanceTest {

    @Test
    fun textBracketIndexIsReusedForRepeatedQueries() {
        val text = buildString {
            append("fun main() {\n")
            repeat(2_000) { append("    doWork()\n") }
            append("}\n")
        }
        val lines = TextLineMap(text)
        val counting = CountingCharSequence(text)
        val scanner = TextBracketScanner(lines, counting, TextSyntax())

        val opener = scanner.lastUnmatchedOpenerOnLine(0)
        assertNotNull("The function brace should be found", opener)
        assertEquals('{', opener!!.char)

        val close = scanner.matchingCloser(opener)
        assertNotNull("The function brace should have a matching closer", close)
        val readsAfterBuild = counting.reads
        assertTrue(
            "The first query should build the document index",
            readsAfterBuild >= text.length / 2,
        )

        repeat(100) {
            assertEquals(close, scanner.matchingCloser(opener))
            assertEquals(opener.offset, scanner.matchingOpener(close!!))
        }

        assertEquals(
            "Repeated queries must not rescan the document from offset 0",
            readsAfterBuild,
            counting.reads,
        )
    }

    @Test(timeout = 15_000)
    fun largeTextFileGuideIsResolvedCorrectly() {
        val bodyLines = 100_000
        val text = buildString {
            append("fun main() {\n")
            repeat(bodyLines) { append("    work()\n") }
            append("}\n")
        }

        var guide: ScopeGuide? = null
        val elapsedMs = kotlin.system.measureTimeMillis {
            guide = detect(text, caretLine = bodyLines / 2)
        }

        assertNotNull("A very large block should still resolve its scope", guide)
        assertEquals(0, guide!!.anchorLine)
        assertEquals(bodyLines + 1, guide.endLine)
        assertTrue(
            "Resolving a 100 000-line block took ${elapsedMs}ms",
            elapsedMs < 5_000,
        )
    }

    @Test(timeout = 15_000)
    fun largeIndentationOnlyScopeIsBoundedAndCorrect() {
        val bodyLines = 80_000
        val text = buildString {
            append("def main():\n")
            repeat(bodyLines) { append("    work()\n") }
            append("\n")
        }

        var guide: ScopeGuide? = null
        val elapsedMs = kotlin.system.measureTimeMillis {
            guide = detect(
                text = text,
                caretLine = bodyLines / 2,
                fileName = "Example.py",
            )
        }

        assertNotNull("A large Python scope should still resolve its end", guide)
        assertEquals(0, guide!!.anchorLine)
        assertEquals(bodyLines, guide.endLine)
        assertTrue(
            "Resolving an 80 000-line indentation scope took ${elapsedMs}ms",
            elapsedMs < 5_000,
        )
    }

    private fun detect(
        text: String,
        caretLine: Int,
        fileName: String = "Example.txt",
    ): ScopeGuide? {
        val lines = TextLineMap(text)
        val brackets = TextBracketScanner(lines, text, TextSyntax.forFileName(fileName))
        return ScopeDetector(text, lines, brackets, tabSize = 4).findGuide(caretLine)
    }

    /** Counts character accesses so a rescan cannot pass unnoticed. */
    private class CountingCharSequence(private val text: String) : CharSequence {
        var reads = 0
            private set

        override val length: Int
            get() = text.length

        override fun get(index: Int): Char {
            reads++
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            text.subSequence(startIndex, endIndex)
    }
}
