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

        val lastLine = minOf(lines.lineCount - 1, closeLine + MAX_LOOKAHEAD_LINES)
        for (line in closeLine + 1..lastLine) {
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
 *
 * The heavy lexical work lives in [TextBracketIndex], which is built once per
 * document snapshot and then reused by every query.
 */
internal class TextBracketScanner(
    private val lines: LineMap,
    private val chars: CharSequence,
    syntax: TextSyntax,
) : BracketResolver {
    private val index: TextBracketIndex by lazy { TextBracketIndex(chars, syntax) }

    override fun lastUnmatchedOpenerOnLine(line: Int): BracketOpener? =
        index.lastUnmatchedOpenerOnLine(
            start = lines.lineStartOffset(line),
            end = lines.lineEndOffset(line),
        )

    override fun matchingCloser(opener: BracketOpener): Int? = index.matchingCloser(opener.offset)

    override fun matchingOpener(closeOffset: Int): Int? = index.matchingOpener(closeOffset)

    override fun findBlockOpenerAfter(closeOffset: Int): Int? {
        val closeLine = lines.lineNumber(closeOffset)
        val closeLineEnd = lines.lineEndOffset(closeLine)
        index.findBraceOnLine(closeOffset + 1, closeLineEnd)?.let { return it }

        val lastLine = minOf(lines.lineCount - 1, closeLine + MAX_LOOKAHEAD_LINES)
        for (line in closeLine + 1..lastLine) {
            val firstCode = index.firstCodeOffset(
                start = lines.lineStartOffset(line),
                end = lines.lineEndOffset(line),
            ) ?: continue
            return if (chars[firstCode] == '{') firstCode else null
        }
        return null
    }

    override fun firstCodeChar(line: Int): Char? = index.firstCodeOffset(
        start = lines.lineStartOffset(line),
        end = lines.lineEndOffset(line),
    )?.let { chars[it] }
}

/**
 * Safety valve for declarations followed by an unusually long run of blank lines.
 * The matcher only needs the first non-blank line after the closing parenthesis.
 */
private const val MAX_LOOKAHEAD_LINES = 5_000

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
