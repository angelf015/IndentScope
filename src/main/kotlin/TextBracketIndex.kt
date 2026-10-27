package com.access

import java.util.BitSet

/**
 * Immutable lexical snapshot used by [TextBracketScanner].
 *
 * Scanning a fallback document from offset 0 on every query is expensive (the
 * previous implementation did it for every opener, closer and code-char lookup).
 * This index tokenizes the document once, records which characters are code and
 * stores every opening/closing bracket pair, so all later queries are O(line)
 * or O(1) instead of O(document).
 *
 * The index is built lazily by [TextBracketScanner] and then reused for every
 * guide update on the same document snapshot.
 */
internal class TextBracketIndex(
    private val chars: CharSequence,
    private val syntax: TextSyntax,
) {
    /** Marks characters that are real code, excluding strings and comments. */
    private val code = BitSet(chars.length)

    /** Maps an opener offset to its closer offset and vice versa. */
    private val pairs = HashMap<Int, Int>()

    /** Offsets of openers that have not found a matching closer yet. */
    private val openers = ArrayDeque<Int>()

    init {
        build()
    }

    fun matchingCloser(openOffset: Int): Int? =
        pairs[openOffset]?.takeIf { it > openOffset }

    fun matchingOpener(closeOffset: Int): Int? =
        pairs[closeOffset]?.takeIf { it < closeOffset }

    /**
     * Last code opener on the line that has no closer on that same line.
     * Openers closed later still act as the line's scope opener, which is what
     * [ScopeDetector] needs, so only closers before [end] are discounted.
     */
    fun lastUnmatchedOpenerOnLine(start: Int, end: Int): BracketOpener? {
        var result: BracketOpener? = null
        var offset = code.nextSetBit(start)
        while (offset in start until end) {
            val ch = chars[offset]
            if (ch == '(' || ch == '[' || ch == '{') {
                val closeOffset = pairs[offset]
                if (closeOffset == null || closeOffset >= end) {
                    result = BracketOpener(ch, offset)
                }
            }
            offset = code.nextSetBit(offset + 1)
        }
        return result
    }

    /** First non-whitespace code character in the range, if any. */
    fun firstCodeOffset(start: Int, end: Int): Int? {
        var offset = code.nextSetBit(start)
        while (offset in start until end) {
            if (!chars[offset].isWhitespace()) return offset
            offset = code.nextSetBit(offset + 1)
        }
        return null
    }

    fun findBraceOnLine(start: Int, end: Int): Int? {
        var offset = code.nextSetBit(start)
        while (offset in start until end) {
            if (chars[offset] == '{') return offset
            offset = code.nextSetBit(offset + 1)
        }
        return null
    }

    private fun build() {
        var i = 0
        var lineComment: String? = null
        var blockDepth = 0
        var string: StringState? = null
        val length = chars.length

        while (i < length) {
            val ch = chars[i]

            if (lineComment != null) {
                if (ch == '\n') lineComment = null
                i++
                continue
            }

            if (blockDepth > 0) {
                if (syntax.nestedBlockComments && ch == '/' && i + 1 < length && chars[i + 1] == '*') {
                    blockDepth++
                    i += 2
                    continue
                }
                if (ch == '*' && i + 1 < length && chars[i + 1] == '/') {
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
                    i + 1 < length &&
                    chars[i + 1] == currentString.delimiter[0]
                ) {
                    i += 2
                    continue
                }
                if (matchesAt(i, length, currentString.delimiter)) {
                    string = null
                    i += currentString.delimiter.length
                    continue
                }
                i++
                continue
            }

            val lineToken = syntax.lineComments.firstOrNull { matchesAt(i, length, it) }
            if (lineToken != null) {
                lineComment = lineToken
                i += lineToken.length
                continue
            }
            if (syntax.blockComments && ch == '/' && i + 1 < length && chars[i + 1] == '*') {
                blockDepth = 1
                i += 2
                continue
            }
            if (syntax.verbatimStrings && ch == '@' && i + 1 < length && chars[i + 1] == '"') {
                string = StringState("\"", escape = false, doubledDelimiter = true)
                i += 2
                continue
            }
            if (syntax.backtickStrings && ch == '`') {
                string = StringState("`", escape = syntax.backtickEscapes, doubledDelimiter = false)
                i++
                continue
            }
            if (syntax.tripleQuotedStrings && matchesAt(i, length, "\"\"\"")) {
                string = StringState("\"\"\"", escape = syntax.tripleQuoteEscapes, doubledDelimiter = false)
                i += 3
                continue
            }
            if (syntax.tripleQuotedStrings && matchesAt(i, length, "'''")) {
                string = StringState("'''", escape = syntax.tripleQuoteEscapes, doubledDelimiter = false)
                i += 3
                continue
            }
            if (ch == '"' || ch == '\'') {
                string = StringState(ch.toString(), escape = true, doubledDelimiter = syntax.doubledQuoteEscapes)
                i++
                continue
            }

            code.set(i)
            when (ch) {
                '(', '[', '{' -> openers.addLast(i)
                ')', ']', '}' -> {
                    val openerOffset = openers.lastOrNull()
                    if (openerOffset != null && chars[openerOffset] == matchingOpener(ch)) {
                        openers.removeLast()
                        pairs[openerOffset] = i
                        pairs[i] = openerOffset
                    }
                }
            }
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
