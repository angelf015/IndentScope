package com.access

import com.intellij.openapi.editor.Document

/**
 * Minimal line model shared by the editor-backed detector and the pure text
 * tests. All offsets refer to the same character sequence used by the detector.
 */
internal interface LineMap {
    val lineCount: Int

    fun lineStartOffset(line: Int): Int

    fun lineEndOffset(line: Int): Int

    fun lineNumber(offset: Int): Int
}

internal class DocumentLineMap(private val document: Document) : LineMap {
    override val lineCount: Int
        get() = document.lineCount

    override fun lineStartOffset(line: Int): Int = document.getLineStartOffset(line)

    override fun lineEndOffset(line: Int): Int = document.getLineEndOffset(line)

    override fun lineNumber(offset: Int): Int =
        document.getLineNumber(offset.coerceIn(0, document.textLength))
}

/**
 * Line map used by pure tests and by the text fallback. It is computed once and
 * keeps the detector independent from the IntelliJ editor API.
 */
internal class TextLineMap(private val text: CharSequence) : LineMap {
    private val starts: IntArray = buildList {
        add(0)
        text.forEachIndexed { index, ch ->
            if (ch == '\n') add(index + 1)
        }
    }.toIntArray()

    override val lineCount: Int
        get() = starts.size

    override fun lineStartOffset(line: Int): Int = starts[line]

    override fun lineEndOffset(line: Int): Int {
        val start = starts[line]
        if (line == starts.lastIndex) return text.length

        var end = starts[line + 1] - 1
        if (end > start && text[end - 1] == '\r') end--
        return end
    }

    override fun lineNumber(offset: Int): Int {
        val target = offset.coerceIn(0, text.length)
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= target) {
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return high.coerceAtLeast(0)
    }
}

internal data class ScopeGuide(
    val anchorLine: Int,
    val endLine: Int,
)

/**
 * Resolves the current scope from indentation and bracket information. It has no
 * dependency on the editor or the platform, so the whole algorithm can be tested
 * with plain JUnit tests and a [TextLineMap].
 */
internal class ScopeDetector(
    private val chars: CharSequence,
    private val lines: LineMap,
    private val brackets: BracketResolver,
    tabSize: Int,
) {
    private val tabSize = tabSize.coerceAtLeast(1)
    private val lineCount = lines.lineCount

    fun findGuide(caretLine: Int): ScopeGuide? {
        if (lineCount == 0) return null

        val baseLine = findNearestNonBlankLine(caretLine.coerceIn(0, lineCount - 1)) ?: return null
        val baseIndent = leadingIndentOrBlank(baseLine)
        if (baseIndent < 0) return null

        // The guide belongs to the innermost block containing the caret. That block is
        // opened either by the base line itself (e.g. when the caret is on the `if`
        // line) or by the nearest non-blank line above it with a smaller indentation.
        // Anchoring the guide to that line is what IntelliJ does for regular indent
        // guides, instead of drawing it next to the block contents.
        val detectedOpenerLine = findScopeOpenerLine(baseLine, baseIndent) ?: return null
        val openerLine = adjustOpenerLine(detectedOpenerLine)
        val indentColumn = leadingIndentOrBlank(openerLine)
        if (indentColumn < 0) return null

        val scope = resolveScope(openerLine)

        val knownEndLine = scope?.endLine
        if (knownEndLine != null) {
            // Exact bracket matching knows the closing line even when the block has
            // an unusual indentation or a bracket-looking token inside a string.
            // A block that opens and closes on the same line has no vertical extent,
            // so it intentionally shows no guide.
            return if (knownEndLine > openerLine) ScopeGuide(scope.anchorLine, knownEndLine) else null
        }

        var lastBodyLine = -1
        var closingLine = -1
        val scopeStartLine = scope?.bodyStartLine ?: openerLine
        for (line in scopeStartLine + 1 until lineCount) {
            val indent = leadingIndentOrBlank(line)
            if (indent < 0) continue

            if (indent <= indentColumn) {
                // The block ends at its closing bracket, which is usually aligned
                // with the line that opens it. Include that line so the guide covers
                // the whole block instead of only the body. This is the fallback for
                // incomplete code, where the platform matcher cannot pair the brace.
                if (scope?.closer != null && brackets.firstCodeChar(line) == scope.closer) {
                    closingLine = line
                }
                break
            }

            lastBodyLine = line
        }

        if (lastBodyLine < 0) return null

        val endLine = if (closingLine >= 0) closingLine else lastBodyLine
        return ScopeGuide(scope?.anchorLine ?: openerLine, endLine)
    }

    /**
     * Indentation of [line] in visual columns, or -1 when the line is blank. Blank and
     * indentation are resolved in the same pass because scope detection walks many
     * lines on every update.
     */
    private fun leadingIndentOrBlank(line: Int): Int {
        var offset = lines.lineStartOffset(line)
        val end = lines.lineEndOffset(line)

        var columns = 0
        while (offset < end) {
            when (chars[offset]) {
                ' ' -> columns += 1
                '\t' -> columns += tabSize - columns % tabSize
                else -> return columns
            }
            offset++
        }
        return -1
    }

    /**
     * When the indentation scan selects the `) {` line of a declaration whose
     * parameters span several lines, walk back to the parenthesis that owns it so
     * the guide still starts on the `public`/`private` line.
     */
    private fun adjustOpenerLine(line: Int): Int {
        val firstOffset = firstSignificantOffset(line) ?: return line
        val first = chars[firstOffset]
        if (first != ')' && first != ']') return line

        val openerOffset = brackets.matchingOpener(firstOffset) ?: return line
        return lines.lineNumber(openerOffset)
    }

    /**
     * Resolves what closes the scope opened on [openerLine]. A multi-line
     * declaration such as `public Foo(\n  args\n) {` opens both a parenthesis and a
     * brace; the brace is the real scope, so the guide must reach its `}` instead of
     * stopping at the signature's `)` or at the last body line.
     *
     * When the platform can pair the bracket, the exact closing line is returned.
     * Indentation is only used later as a fallback for incomplete code.
     */
    private fun resolveScope(openerLine: Int): ScopeInfo? {
        val opener = brackets.lastUnmatchedOpenerOnLine(openerLine) ?: return null

        if (opener.char == '{') {
            val closeOffset = brackets.matchingCloser(opener)
            return ScopeInfo(
                closer = '}',
                bodyStartLine = openerLine,
                endLine = closeOffset?.let { lines.lineNumber(it) },
                anchorLine = allmanAnchorLine(opener, openerLine),
            )
        }

        val closeOffset = brackets.matchingCloser(opener) ?: return ScopeInfo(
            closer = matchingCloser(opener.char),
            bodyStartLine = openerLine,
            anchorLine = openerLine,
        )

        val blockOpenOffset = brackets.findBlockOpenerAfter(closeOffset)
        if (blockOpenOffset != null) {
            val blockScope = BracketOpener('{', blockOpenOffset)
            val blockCloseOffset = brackets.matchingCloser(blockScope)
            return ScopeInfo(
                closer = '}',
                bodyStartLine = lines.lineNumber(blockOpenOffset),
                endLine = blockCloseOffset?.let { lines.lineNumber(it) },
                anchorLine = openerLine,
            )
        }

        return ScopeInfo(
            closer = matchingCloser(opener.char),
            bodyStartLine = openerLine,
            endLine = lines.lineNumber(closeOffset),
            anchorLine = openerLine,
        )
    }

    /**
     * Allman style puts the `{` on its own line. The platform matcher already knows
     * the declaration line for methods and control statements; for language
     * constructs where it returns the brace line (for example Java classes), walk
     * back to the previous non-blank line and reuse the same continuation logic as
     * multi-line signatures.
     */
    private fun allmanAnchorLine(opener: BracketOpener, openerLine: Int): Int {
        val firstOffset = firstSignificantOffset(openerLine) ?: return openerLine
        if (firstOffset != opener.offset) return openerLine

        val constructStart = brackets.codeConstructStart(opener.offset)
        if (constructStart != null) {
            val constructLine = lines.lineNumber(constructStart)
            if (constructLine < openerLine) {
                return adjustOpenerLine(constructLine)
            }
        }

        val previousLine = findPreviousNonBlankLine(openerLine) ?: return openerLine
        if (!isAllmanAnchorCandidate(previousLine)) return openerLine
        return adjustOpenerLine(previousLine)
    }

    /**
     * The previous line is a plausible declaration/header for an Allman brace when
     * it is not a comment, a statement terminator or another brace.
     */
    private fun isAllmanAnchorCandidate(line: Int): Boolean {
        val start = firstSignificantOffset(line) ?: return false
        val first = chars[start]
        if (first == '}' || first == '{' || first == ';' || first == '*' || first == '/') return false

        val end = lines.lineEndOffset(line)
        var last = end - 1
        while (last >= start && chars[last].isWhitespace()) last--
        if (last < start) return false

        return chars[last] != ';' && chars[last] != '{' && chars[last] != '}' && chars[last] != ','
    }

    private fun findPreviousNonBlankLine(line: Int): Int? {
        for (previous in line - 1 downTo 0) {
            if (leadingIndentOrBlank(previous) >= 0) return previous
        }
        return null
    }

    private fun firstSignificantOffset(line: Int): Int? {
        val start = lines.lineStartOffset(line)
        val end = lines.lineEndOffset(line)

        for (offset in start until end) {
            if (!chars[offset].isWhitespace()) return offset
        }
        return null
    }

    private fun findScopeOpenerLine(baseLine: Int, baseIndent: Int): Int? {
        if (opensBlock(baseLine, baseIndent) || opensMultilineBracketScope(baseLine)) return baseLine

        for (line in baseLine - 1 downTo 0) {
            val indent = leadingIndentOrBlank(line)
            if (indent >= 0 && indent < baseIndent) return line
        }
        return null
    }

    /**
     * An empty block has no indented body to detect, but its brackets still span
     * several lines. Treat that as a scope so the user sees the guide while the caret
     * is on the opener or on one of its blank body lines.
     */
    private fun opensMultilineBracketScope(line: Int): Boolean {
        val opener = brackets.lastUnmatchedOpenerOnLine(line) ?: return false
        val closeOffset = brackets.matchingCloser(opener) ?: return false
        return lines.lineNumber(closeOffset) > line
    }

    private fun opensBlock(line: Int, indent: Int): Boolean {
        for (nextLine in line + 1 until lineCount) {
            val nextIndent = leadingIndentOrBlank(nextLine)
            if (nextIndent < 0) continue
            return nextIndent > indent
        }
        return false
    }

    private fun findNearestNonBlankLine(caretLine: Int): Int? {
        if (leadingIndentOrBlank(caretLine) >= 0) return caretLine

        var above: Int? = null
        for (line in caretLine - 1 downTo 0) {
            if (leadingIndentOrBlank(line) >= 0) {
                above = line
                break
            }
        }
        var below: Int? = null
        for (line in caretLine + 1 until lineCount) {
            if (leadingIndentOrBlank(line) >= 0) {
                below = line
                break
            }
        }

        return when {
            above == null -> below
            below == null -> above
            // A blank line between an opener and its body belongs to the body, and so
            // does one between the body and the line that closes the block.
            leadingIndentOrBlank(below) > leadingIndentOrBlank(above) -> below
            else -> above
        }
    }

    private data class ScopeInfo(
        val closer: Char?,
        val bodyStartLine: Int,
        val endLine: Int? = null,
        val anchorLine: Int,
    )
}
