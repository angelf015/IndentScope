package com.access

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

internal data class BracketOpener(
    val char: Char,
    val offset: Int,
)

internal fun matchingOpener(closer: Char): Char? = when (closer) {
    ')' -> '('
    ']' -> '['
    '}' -> '{'
    else -> null
}

internal fun matchingCloser(opener: Char): Char? = when (opener) {
    '(' -> ')'
    '[' -> ']'
    '{' -> '}'
    else -> null
}

/**
 * Bracket operations needed by [ScopeDetector]. The platform-backed scanner and
 * the pure text fallback implement the same contract.
 */
internal interface BracketResolver {
    fun lastUnmatchedOpenerOnLine(line: Int): BracketOpener?

    fun matchingCloser(opener: BracketOpener): Int?

    fun matchingOpener(closeOffset: Int): Int?

    fun firstCodeChar(line: Int): Char?

    fun findBlockOpenerAfter(closeOffset: Int): Int?

    fun codeConstructStart(offset: Int): Int? = null
}

/**
 * Finds code brackets while ignoring strings and comments. For real languages it
 * delegates to the platform brace matcher, which uses the language highlighter and
 * therefore understands that language's strings, comments and nested constructions.
 * For plain text, scratch files and editors without a language matcher it falls back
 * to [TextBracketScanner].
 */
internal class BracketScanner(
    private val editor: EditorEx,
    private val chars: CharSequence,
    private val lines: LineMap = DocumentLineMap(editor.document),
) : BracketResolver {
    private val psiFile: PsiFile? = editor.project?.let {
        PsiDocumentManager.getInstance(it).getPsiFile(editor.document)
    }
    private val fileType: FileType? = psiFile?.fileType
    private val platformMatcher: BraceMatcher? = if (psiFile != null && fileType != null && !isPlainText(psiFile)) {
        BraceMatchingUtil.getBraceMatcher(fileType, psiFile.language)
    } else {
        null
    }
    private val usePlatformMatcher = platformMatcher != null && psiFile != null && fileType != null
    private val textScanner = TextBracketScanner(lines, chars, TextSyntax.forFileName(psiFile?.name))

    override fun lastUnmatchedOpenerOnLine(line: Int): BracketOpener? =
        if (usePlatformMatcher) platformLastUnmatchedOpener(line) else textScanner.lastUnmatchedOpenerOnLine(line)

    override fun matchingCloser(opener: BracketOpener): Int? =
        if (usePlatformMatcher) platformMatchingCloser(opener) else textScanner.matchingCloser(opener)

    override fun matchingOpener(closeOffset: Int): Int? =
        if (usePlatformMatcher) platformMatchingOpener(closeOffset) else textScanner.matchingOpener(closeOffset)

    override fun firstCodeChar(line: Int): Char? =
        if (usePlatformMatcher) platformFirstCodeChar(line) else textScanner.firstCodeChar(line)

    override fun findBlockOpenerAfter(closeOffset: Int): Int? =
        if (usePlatformMatcher) platformFindBlockOpenerAfter(closeOffset) else textScanner.findBlockOpenerAfter(closeOffset)

    /**
     * Start of the declaration that owns the brace at [offset]. Platform matchers
     * know this for languages such as Java and Kotlin, which is what lets Allman
     * style show the guide from the declaration line instead of the `{` line.
     */
    override fun codeConstructStart(offset: Int): Int? {
        if (!usePlatformMatcher) return null
        val file = psiFile ?: return null
        val matcher = platformMatcher ?: return null
        return try {
            matcher.getCodeConstructStart(file, offset).takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun platformLastUnmatchedOpener(line: Int): BracketOpener? {
        val type = fileType ?: return null
        val start = lines.lineStartOffset(line)
        val end = lines.lineEndOffset(line)
        val stack = ArrayDeque<BracketOpener>()

        var i = start
        while (i < end) {
            when (val ch = chars[i]) {
                '(', '[', '{' -> if (isPlatformBrace(i, ch, type)) {
                    stack.addLast(BracketOpener(ch, i))
                }

                ')', ']', '}' -> {
                    if (isPlatformBrace(i, ch, type) &&
                        stack.isNotEmpty() &&
                        matchingOpener(ch) == stack.last().char
                    ) {
                        stack.removeLast()
                    }
                }
            }
            i++
        }
        return stack.lastOrNull()
    }

    private fun platformMatchingCloser(opener: BracketOpener): Int? {
        val type = fileType ?: return null
        val iterator = editor.highlighter.createIterator(opener.offset)
        if (!BraceMatchingUtil.isLBraceToken(iterator, chars, type)) return null
        return if (BraceMatchingUtil.matchBrace(chars, type, iterator, true)) iterator.start else null
    }

    private fun platformMatchingOpener(closeOffset: Int): Int? {
        val type = fileType ?: return null
        val iterator = editor.highlighter.createIterator(closeOffset)
        if (!BraceMatchingUtil.isRBraceToken(iterator, chars, type)) return null
        return if (BraceMatchingUtil.matchBrace(chars, type, iterator, false)) iterator.start else null
    }

    private fun platformFirstCodeChar(line: Int): Char? {
        val type = fileType ?: return null
        val start = lines.lineStartOffset(line)
        val end = lines.lineEndOffset(line)

        var i = start
        while (i < end) {
            val ch = chars[i]
            if (!ch.isWhitespace()) {
                return if (ch in "()[]{}" && isPlatformBrace(i, ch, type)) ch else null
            }
            i++
        }
        return null
    }

    private fun platformFindBlockOpenerAfter(closeOffset: Int): Int? {
        val type = fileType ?: return null
        val closeLine = lines.lineNumber(closeOffset)
        val closeLineEnd = lines.lineEndOffset(closeLine)
        findPlatformBraceOnLine(closeOffset + 1, closeLineEnd, '{', type)?.let { return it }

        for (line in closeLine + 1 until lines.lineCount) {
            val start = lines.lineStartOffset(line)
            val end = lines.lineEndOffset(line)
            var i = start
            while (i < end && chars[i].isWhitespace()) i++
            if (i >= end) continue
            return if (chars[i] == '{' && isPlatformBrace(i, '{', type)) i else null
        }
        return null
    }

    private fun findPlatformBraceOnLine(start: Int, end: Int, brace: Char, type: FileType): Int? {
        var i = start
        while (i < end) {
            if (chars[i] == brace && isPlatformBrace(i, brace, type)) return i
            i++
        }
        return null
    }

    private fun isPlatformBrace(offset: Int, ch: Char, type: FileType): Boolean {
        val iterator = editor.highlighter.createIterator(offset)
        return when (ch) {
            '(', '[', '{' -> BraceMatchingUtil.isLBraceToken(iterator, chars, type)
            ')', ']', '}' -> BraceMatchingUtil.isRBraceToken(iterator, chars, type)
            else -> false
        }
    }

    private fun isPlainText(file: PsiFile): Boolean =
        file.language.isKindOf(PlainTextLanguage.INSTANCE) || file.language.id == "TEXT"
}

/**
 * Language-agnostic fallback used for plain text and for editors without a platform
 * brace matcher. It understands the common string and comment forms so a bracket in
 * a string, a template literal or a block comment is never treated as code.
 */
internal class TextBracketScanner(
    private val lines: LineMap,
    private val chars: CharSequence,
    private val syntax: TextSyntax,
) : BracketResolver {
    override fun lastUnmatchedOpenerOnLine(line: Int): BracketOpener? {
        val start = lines.lineStartOffset(line)
        val end = lines.lineEndOffset(line)
        val stack = ArrayDeque<BracketOpener>()

        // Start at the beginning of the document so a line that begins inside a
        // multi-line string or block comment is not mistaken for code.
        scanCode(0, end) { offset, ch ->
            when (ch) {
                '(', '[', '{' -> stack.addLast(BracketOpener(ch, offset))
                ')', ']', '}' -> {
                    if (stack.isNotEmpty() && matchingOpener(ch) == stack.last().char) {
                        stack.removeLast()
                    }
                }
            }
            true
        }
        return stack.lastOrNull { it.offset >= start }
    }

    override fun matchingCloser(opener: BracketOpener): Int? {
        val stack = ArrayDeque<Char>()
        stack.addLast(opener.char)
        var result: Int? = null

        scanCode(opener.offset + 1, chars.length) { offset, ch ->
            when (ch) {
                '(', '[', '{' -> stack.addLast(ch)
                ')', ']', '}' -> {
                    if (stack.lastOrNull() == matchingOpener(ch)) {
                        stack.removeLast()
                        if (stack.isEmpty()) {
                            result = offset
                            return@scanCode false
                        }
                    }
                }
            }
            true
        }
        return result
    }

    override fun matchingOpener(closeOffset: Int): Int? {
        val stack = ArrayDeque<Int>()
        var result: Int? = null

        scanCode(0, closeOffset + 1) { offset, ch ->
            when (ch) {
                '(', '[', '{' -> stack.addLast(offset)
                ')', ']', '}' -> {
                    if (stack.isNotEmpty() && chars[stack.last()] == matchingOpener(ch)) {
                        val opener = stack.removeLast()
                        if (offset == closeOffset) {
                            result = opener
                            return@scanCode false
                        }
                    }
                }
            }
            true
        }
        return result
    }

    override fun findBlockOpenerAfter(closeOffset: Int): Int? {
        val closeLine = lines.lineNumber(closeOffset)
        val closeLineEnd = lines.lineEndOffset(closeLine)
        findBraceOnLine(closeOffset + 1, closeLineEnd)?.let { return it }

        for (line in closeLine + 1 until lines.lineCount) {
            val firstCode = firstCodeOffset(line) ?: continue
            return if (chars[firstCode] == '{') firstCode else null
        }
        return null
    }

    override fun firstCodeChar(line: Int): Char? = firstCodeOffset(line)?.let { chars[it] }

    private fun firstCodeOffset(line: Int): Int? {
        val start = lines.lineStartOffset(line)
        val end = lines.lineEndOffset(line)
        var result: Int? = null

        // Scan from the document start so a line inside a multi-line string or
        // comment does not expose bracket-like content as code.
        scanCode(0, end) { offset, _ ->
            if (offset < start) {
                true
            } else {
                result = offset
                false
            }
        }
        return result
    }

    private fun findBraceOnLine(start: Int, end: Int): Int? {
        var result: Int? = null
        scanCode(start, end) { offset, ch ->
            if (ch == '{') {
                result = offset
                false
            } else {
                true
            }
        }
        return result
    }

    private inline fun scanCode(start: Int, end: Int, visit: (Int, Char) -> Boolean) {
        var i = start
        var lineComment: String? = null
        var blockDepth = 0
        var string: StringState? = null

        while (i < end) {
            val ch = chars[i]

            if (lineComment != null) {
                if (ch == '\n') lineComment = null
                i++
                continue
            }

            if (blockDepth > 0) {
                if (syntax.nestedBlockComments && ch == '/' && i + 1 < end && chars[i + 1] == '*') {
                    blockDepth++
                    i += 2
                    continue
                }
                if (ch == '*' && i + 1 < end && chars[i + 1] == '/') {
                    blockDepth--
                    i += 2
                    continue
                }
                i++
                continue
            }

            val currentString = string
            if (currentString != null) {
                if (currentString.escape && ch == '\\') {
                    i += 2
                    continue
                }
                if (currentString.doubledDelimiter &&
                    ch == currentString.delimiter[0] &&
                    i + 1 < end &&
                    chars[i + 1] == currentString.delimiter[0]
                ) {
                    i += 2
                    continue
                }
                if (matchesAt(i, end, currentString.delimiter)) {
                    string = null
                    i += currentString.delimiter.length
                    continue
                }
                i++
                continue
            }

            val lineToken = syntax.lineComments.firstOrNull { matchesAt(i, end, it) }
            if (lineToken != null) {
                lineComment = lineToken
                i += lineToken.length
                continue
            }
            if (syntax.blockComments && ch == '/' && i + 1 < end && chars[i + 1] == '*') {
                blockDepth = 1
                i += 2
                continue
            }
            if (syntax.verbatimStrings && ch == '@' && i + 1 < end && chars[i + 1] == '"') {
                string = StringState("\"", escape = false, doubledDelimiter = true)
                i += 2
                continue
            }
            if (syntax.backtickStrings && ch == '`') {
                string = StringState("`", escape = syntax.backtickEscapes, doubledDelimiter = false)
                i++
                continue
            }
            if (syntax.tripleQuotedStrings && matchesAt(i, end, "\"\"\"")) {
                string = StringState("\"\"\"", escape = syntax.tripleQuoteEscapes, doubledDelimiter = false)
                i += 3
                continue
            }
            if (syntax.tripleQuotedStrings && matchesAt(i, end, "'''")) {
                string = StringState("'''", escape = syntax.tripleQuoteEscapes, doubledDelimiter = false)
                i += 3
                continue
            }
            if (ch == '"' || ch == '\'') {
                string = StringState(ch.toString(), escape = true, doubledDelimiter = syntax.doubledQuoteEscapes)
                i++
                continue
            }

            if (!visit(i, ch)) return
            i++
        }
    }

    private fun matchesAt(offset: Int, end: Int, token: String): Boolean {
        if (offset + token.length > end) return false
        for (index in token.indices) {
            if (chars[offset + index] != token[index]) return false
        }
        return true
    }

    private data class StringState(
        val delimiter: String,
        val escape: Boolean,
        val doubledDelimiter: Boolean,
    )
}

internal data class TextSyntax(
    val lineComments: List<String> = listOf("//"),
    val blockComments: Boolean = true,
    val nestedBlockComments: Boolean = false,
    val backtickStrings: Boolean = true,
    val backtickEscapes: Boolean = true,
    val tripleQuotedStrings: Boolean = true,
    val tripleQuoteEscapes: Boolean = true,
    val verbatimStrings: Boolean = true,
    val doubledQuoteEscapes: Boolean = false,
) {
    companion object {
        fun forFileName(name: String?): TextSyntax {
            val extension = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return when (extension) {
                "py", "pyw" -> TextSyntax(
                    lineComments = listOf("#"),
                    blockComments = false,
                    backtickStrings = false,
                )

                "sh", "bash", "zsh", "fish" -> TextSyntax(
                    lineComments = listOf("#"),
                    blockComments = false,
                )

                "rb" -> TextSyntax(
                    lineComments = listOf("#"),
                    blockComments = false,
                    backtickStrings = false,
                )

                "sql" -> TextSyntax(
                    lineComments = listOf("--"),
                    doubledQuoteEscapes = true,
                )

                "kt", "kts" -> TextSyntax(
                    nestedBlockComments = true,
                    backtickStrings = false,
                    tripleQuoteEscapes = false,
                )

                "swift", "rs" -> TextSyntax(
                    nestedBlockComments = true,
                )

                "go" -> TextSyntax(
                    backtickEscapes = false,
                )

                "cs" -> TextSyntax(
                    verbatimStrings = true,
                )

                else -> TextSyntax()
            }
        }
    }
}
