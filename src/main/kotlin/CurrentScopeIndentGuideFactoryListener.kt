package com.access

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import java.awt.*
import java.awt.geom.Path2D

/**
 * Registers editors with [IndentScopeService]. The application service owns the
 * per-editor controllers and is disposed by the platform, so the listener does not
 * need its own lifecycle cleanup.
 */
class CurrentScopeIndentGuideFactoryListener : EditorFactoryListener {
    private val service: IndentScopeService by lazy { IndentScopeService.getInstance() }

    init {
        // When the plugin is installed dynamically, the IDE does not recreate the
        // editors that are already open, so the factory listener would never see them.
        service.registerOpenEditors()
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorEx ?: return
        if (editor.isOneLineMode) return

        service.editorCreated(editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorEx ?: return
        service.editorReleased(editor)
    }
}

internal class CurrentScopeIndentGuideController(
    private val editor: EditorEx,
) : CaretListener, DocumentListener, Disposable {

    private val listenerDisposable: Disposable = Disposer.newDisposable()
    private val updateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, listenerDisposable)
    private val updateRequest = Runnable { updateHighlight() }

    private var rangeHighlighter: RangeHighlighter? = null
    private var appliedGuide: ScopeGuide? = null
    private var cachedGuide: ScopeGuide? = null
    private var cachedCaretLine = -1
    private var cachedModificationStamp = Long.MIN_VALUE
    private var cachedTabSize = -1
    private var hasCachedGuide = false
    private var savedNativeIndentGuides: Boolean? = null
    private var enabled = false
    private var disposed = false

    init {
        editor.caretModel.addCaretListener(this)
        editor.document.addDocumentListener(this, listenerDisposable)
    }

    /**
     * Activates or deactivates the custom guide. `EditorSettings` is created per
     * editor, so the native setting is captured and restored for this editor only.
     * The service calls this for every open editor when the global toggle changes.
     */
    fun setEnabled(value: Boolean) {
        if (disposed || editor.isDisposed || enabled == value) return
        enabled = value

        if (value) {
            savedNativeIndentGuides = editor.settings.isIndentGuidesShown
            editor.settings.isIndentGuidesShown = false
            updateHighlight()
        } else {
            updateAlarm.cancelAllRequests()
            clearHighlight()
            savedNativeIndentGuides?.let { editor.settings.isIndentGuidesShown = it }
            savedNativeIndentGuides = null
        }
    }

    override fun caretPositionChanged(event: CaretEvent) {
        if (!enabled) return
        // The guide depends on the caret line only, so horizontal moves are free.
        if (event.oldPosition.line == event.newPosition.line) return
        scheduleUpdate()
    }

    override fun documentChanged(event: DocumentEvent) {
        if (!enabled) return
        scheduleUpdate()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        updateAlarm.cancelAllRequests()
        if (!editor.isDisposed) {
            editor.caretModel.removeCaretListener(this)
            savedNativeIndentGuides?.let { editor.settings.isIndentGuidesShown = it }
        }
        Disposer.dispose(listenerDisposable)
        clearHighlight()
    }

    /**
     * Coalesces bursts of caret/document events into a single recomputation, which
     * keeps fast typing and rapid navigation cheap on large files.
     */
    private fun scheduleUpdate() {
        if (!enabled || disposed || editor.isDisposed) return
        updateAlarm.cancelRequest(updateRequest)
        updateAlarm.addRequest(updateRequest, UPDATE_DELAY_MS)
    }

    private fun updateHighlight() {
        if (!enabled || disposed || editor.isDisposed) return

        val guide = getCurrentGuide()
        if (guide == appliedGuide && (guide == null || rangeHighlighter?.isValid == true)) return

        clearHighlight()
        if (guide == null) return

        val document = editor.document
        val highlighter = editor.markupModel.addRangeHighlighter(
            document.getLineStartOffset(guide.anchorLine),
            document.getLineEndOffset(guide.endLine),
            HighlighterLayer.CARET_ROW + 1,
            TextAttributes(),
            HighlighterTargetArea.EXACT_RANGE,
        )
        highlighter.customRenderer = ActiveIndentGuideRenderer(
            anchorLine = guide.anchorLine,
            endLine = guide.endLine,
        )
        rangeHighlighter = highlighter
        appliedGuide = guide
    }

    /**
     * The scan is cached by caret line, document modification stamp and tab size, so
     * repeated updates that cannot change the answer reuse the previous guide.
     */
    private fun getCurrentGuide(): ScopeGuide? {
        val caretLine = editor.caretModel.logicalPosition.line
        val stamp = editor.document.modificationStamp
        val tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
        if (hasCachedGuide &&
            caretLine == cachedCaretLine &&
            stamp == cachedModificationStamp &&
            tabSize == cachedTabSize
        ) {
            return cachedGuide
        }

        val guide = computeCurrentScopeGuide(tabSize)
        cachedGuide = guide
        cachedCaretLine = caretLine
        cachedModificationStamp = stamp
        cachedTabSize = tabSize
        hasCachedGuide = true
        return guide
    }

    private fun computeCurrentScopeGuide(tabSize: Int): ScopeGuide? {
        val document = editor.document
        val lineCount = document.lineCount
        if (lineCount == 0) return null

        val chars = document.charsSequence

        val caretLine = editor.caretModel.logicalPosition.line.coerceIn(0, lineCount - 1)
        val baseLine = findNearestNonBlankLine(chars, caretLine, lineCount, tabSize) ?: return null
        val baseIndent = leadingIndentOrBlank(chars, baseLine, tabSize)
        if (baseIndent < 0) return null

        // The guide belongs to the innermost block containing the caret. That block is
        // opened either by the base line itself (e.g. when the caret is on the `if`
        // line) or by the nearest non-blank line above it with a smaller indentation.
        // Anchoring the guide to that line is what IntelliJ does for regular indent
        // guides, instead of drawing it next to the block contents.
        val detectedOpenerLine =
            findScopeOpenerLine(chars, baseLine, baseIndent, lineCount, tabSize) ?: return null
        val openerLine = adjustOpenerLine(chars, detectedOpenerLine)
        val indentColumn = leadingIndentOrBlank(chars, openerLine, tabSize)
        if (indentColumn < 0) return null

        val scope = resolveScope(chars, openerLine)
        // A top-level class/function has no indentation to its left, but it is still a
        // real bracket scope. Draw its guide at the first character instead. Do not
        // create guides for indentation-only top-level statements, which have no
        // closing bracket of their own.
        if (indentColumn == 0 && scope == null) return null

        val knownEndLine = scope?.endLine
        if (knownEndLine != null) {
            // Multi-line parentheses/brackets know their closing line exactly.
            return if (knownEndLine > openerLine) ScopeGuide(openerLine, knownEndLine) else null
        }

        var lastBodyLine = -1
        var closingLine = -1
        val scopeStartLine = scope?.bodyStartLine ?: openerLine
        for (line in scopeStartLine + 1 until lineCount) {
            val indent = leadingIndentOrBlank(chars, line, tabSize)
            if (indent < 0) continue

            if (indent <= indentColumn) {
                // The block ends at its closing bracket, which is usually aligned
                // with the line that opens it. Include that line so the guide covers
                // the whole block instead of only the body. This applies to braces
                // as well as multi-line parentheses and brackets.
                if (scope?.closer != null && firstSignificantChar(chars, line) == scope.closer) {
                    closingLine = line
                }
                break
            }

            lastBodyLine = line
        }

        if (lastBodyLine < 0) return null

        val endLine = if (closingLine >= 0) closingLine else lastBodyLine
        return ScopeGuide(openerLine, endLine)
    }

    /**
     * Indentation of [line] in visual columns, or -1 when the line is blank. Blank and
     * indentation are resolved in the same pass because scope detection walks many
     * lines on every update.
     */
    private fun leadingIndentOrBlank(chars: CharSequence, line: Int, tabSize: Int): Int {
        val document = editor.document
        var offset = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)

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

    private fun firstSignificantChar(chars: CharSequence, line: Int): Char? {
        val document = editor.document
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)

        for (i in start until end) {
            if (!chars[i].isWhitespace()) return chars[i]
        }
        return null
    }

    /**
     * When the indentation scan selects the `) {` line of a declaration whose
     * parameters span several lines, walk back to the parenthesis that owns it so
     * the guide still starts on the `public`/`private` line.
     */
    private fun adjustOpenerLine(chars: CharSequence, line: Int): Int {
        val firstOffset = firstSignificantOffset(chars, line) ?: return line
        val first = chars[firstOffset]
        if (first != ')' && first != ']') return line

        val openerOffset = findMatchingOpenerOffset(chars, firstOffset) ?: return line
        return editor.document.getLineNumber(openerOffset)
    }

    /**
     * Resolves what closes the scope opened on [openerLine]. A multi-line
     * declaration such as `public Foo(\n  args\n) {` opens both a parenthesis and a
     * brace; the brace is the real scope, so the guide must reach its `}` instead of
     * stopping at the signature's `)` or at the last body line.
     */
    private fun resolveScope(chars: CharSequence, openerLine: Int): ScopeInfo? {
        val opener = findLastUnmatchedOpener(chars, openerLine) ?: return null

        if (opener.char == '{') {
            return ScopeInfo(closer = '}', bodyStartLine = openerLine)
        }

        val closeOffset = findMatchingCloser(chars, opener) ?: return ScopeInfo(
            closer = matchingCloser(opener.char),
            bodyStartLine = openerLine,
        )

        val blockOpenOffset = findBlockOpenerAfter(chars, closeOffset)
        if (blockOpenOffset != null) {
            return ScopeInfo(
                closer = '}',
                bodyStartLine = editor.document.getLineNumber(blockOpenOffset),
            )
        }

        return ScopeInfo(
            closer = matchingCloser(opener.char),
            bodyStartLine = openerLine,
            endLine = editor.document.getLineNumber(closeOffset),
        )
    }

    /**
     * Last `(`, `[` or `{` on [line] that is still open at the end of the line.
     */
    private fun findLastUnmatchedOpener(chars: CharSequence, line: Int): BracketOpener? {
        val document = editor.document
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)

        val openers = ArrayDeque<BracketOpener>()
        var quote: Char? = null
        var i = start
        while (i < end) {
            val ch = chars[i]
            if (quote != null) {
                when {
                    ch == '\\' -> i++ // Skip the escaped character.
                    ch == quote -> quote = null
                }
                i++
                continue
            }

            when (ch) {
                '"', '\'' -> quote = ch
                '/' -> if (i + 1 < end && chars[i + 1] == '/') break
                '(', '[', '{' -> openers.addLast(BracketOpener(ch, i))
                ')', ']', '}' -> {
                    if (openers.isNotEmpty() && openers.last().char == matchingOpener(ch)) {
                        openers.removeLast()
                    }
                }
            }
            i++
        }

        return openers.lastOrNull()
    }

    /**
     * Offset of the bracket that closes [opener], or null when it is not closed.
     */
    private fun findMatchingCloser(chars: CharSequence, opener: BracketOpener): Int? {
        val stack = ArrayDeque<Char>()
        stack.addLast(opener.char)

        var quote: Char? = null
        var lineComment = false
        var blockComment = false
        var i = opener.offset + 1
        while (i < chars.length) {
            val ch = chars[i]
            if (lineComment) {
                if (ch == '\n') lineComment = false
                i++
                continue
            }
            if (blockComment) {
                if (ch == '*' && i + 1 < chars.length && chars[i + 1] == '/') {
                    blockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (quote != null) {
                when {
                    ch == '\\' -> i += 2
                    ch == quote -> {
                        quote = null
                        i++
                    }
                    else -> i++
                }
                continue
            }

            when (ch) {
                '"', '\'' -> quote = ch
                '/' -> {
                    if (i + 1 < chars.length && chars[i + 1] == '/') {
                        lineComment = true
                        i += 2
                        continue
                    }
                    if (i + 1 < chars.length && chars[i + 1] == '*') {
                        blockComment = true
                        i += 2
                        continue
                    }
                }
                '(', '[', '{' -> stack.addLast(ch)
                ')', ']', '}' -> {
                    if (stack.lastOrNull() == matchingOpener(ch)) {
                        stack.removeLast()
                        if (stack.isEmpty()) return i
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Offset of the opener matching the closing bracket at [closeOffset]. Used to
     * recover the declaration line of a `) {` header continuation.
     */
    private fun findMatchingOpenerOffset(chars: CharSequence, closeOffset: Int): Int? {
        val stack = ArrayDeque<Int>()

        var quote: Char? = null
        var lineComment = false
        var blockComment = false
        var i = 0
        while (i <= closeOffset) {
            val ch = chars[i]
            if (lineComment) {
                if (ch == '\n') lineComment = false
                i++
                continue
            }
            if (blockComment) {
                if (ch == '*' && i + 1 < chars.length && chars[i + 1] == '/') {
                    blockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (quote != null) {
                when {
                    ch == '\\' -> i += 2
                    ch == quote -> {
                        quote = null
                        i++
                    }
                    else -> i++
                }
                continue
            }

            when (ch) {
                '"', '\'' -> quote = ch
                '/' -> {
                    if (i + 1 < chars.length && chars[i + 1] == '/') {
                        lineComment = true
                        i += 2
                        continue
                    }
                    if (i + 1 < chars.length && chars[i + 1] == '*') {
                        blockComment = true
                        i += 2
                        continue
                    }
                }
                '(', '[', '{' -> stack.addLast(i)
                ')', ']', '}' -> {
                    if (stack.isNotEmpty() && chars[stack.last()] == matchingOpener(ch)) {
                        val opener = stack.removeLast()
                        if (i == closeOffset) return opener
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Finds the `{` that starts the body after a declaration's closing `)`. It may be
     * on the same line (`) {`) or on the next non-blank line for Allman-style code.
     */
    private fun findBlockOpenerAfter(chars: CharSequence, closeOffset: Int): Int? {
        val document = editor.document
        val closeLine = document.getLineNumber(closeOffset)
        val closeLineEnd = document.getLineEndOffset(closeLine)

        findBraceOnLine(chars, closeOffset + 1, closeLineEnd)?.let { return it }

        for (line in closeLine + 1 until document.lineCount) {
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            var offset = start
            while (offset < end && chars[offset].isWhitespace()) offset++
            if (offset >= end) continue
            return if (chars[offset] == '{') offset else null
        }
        return null
    }

    private fun findBraceOnLine(chars: CharSequence, start: Int, end: Int): Int? {
        var quote: Char? = null
        var i = start
        while (i < end) {
            val ch = chars[i]
            if (quote != null) {
                when {
                    ch == '\\' -> i++
                    ch == quote -> quote = null
                }
                i++
                continue
            }

            when (ch) {
                '"', '\'' -> quote = ch
                '/' -> if (i + 1 < end && chars[i + 1] == '/') return null
                '{' -> return i
            }
            i++
        }
        return null
    }

    private fun matchingOpener(closer: Char): Char? = when (closer) {
        ')' -> '('
        ']' -> '['
        '}' -> '{'
        else -> null
    }

    private fun matchingCloser(opener: Char): Char? = when (opener) {
        '(' -> ')'
        '[' -> ']'
        '{' -> '}'
        else -> null
    }

    private fun firstSignificantOffset(chars: CharSequence, line: Int): Int? {
        val document = editor.document
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)

        for (i in start until end) {
            if (!chars[i].isWhitespace()) return i
        }
        return null
    }

    private fun findScopeOpenerLine(
        chars: CharSequence,
        baseLine: Int,
        baseIndent: Int,
        lineCount: Int,
        tabSize: Int,
    ): Int? {
        if (opensBlock(chars, baseLine, baseIndent, lineCount, tabSize)) return baseLine

        for (line in baseLine - 1 downTo 0) {
            val indent = leadingIndentOrBlank(chars, line, tabSize)
            if (indent >= 0 && indent < baseIndent) return line
        }
        return null
    }

    private fun opensBlock(
        chars: CharSequence,
        line: Int,
        indent: Int,
        lineCount: Int,
        tabSize: Int,
    ): Boolean {
        for (nextLine in line + 1 until lineCount) {
            val nextIndent = leadingIndentOrBlank(chars, nextLine, tabSize)
            if (nextIndent < 0) continue
            return nextIndent > indent
        }
        return false
    }

    private fun findNearestNonBlankLine(
        chars: CharSequence,
        caretLine: Int,
        lineCount: Int,
        tabSize: Int,
    ): Int? {
        if (leadingIndentOrBlank(chars, caretLine, tabSize) >= 0) return caretLine

        var above: Int? = null
        for (line in caretLine - 1 downTo 0) {
            if (leadingIndentOrBlank(chars, line, tabSize) >= 0) {
                above = line
                break
            }
        }
        var below: Int? = null
        for (line in caretLine + 1 until lineCount) {
            if (leadingIndentOrBlank(chars, line, tabSize) >= 0) {
                below = line
                break
            }
        }

        return when {
            above == null -> below
            below == null -> above
            // A blank line between an opener and its body belongs to the body, and so
            // does one between the body and the line that closes the block.
            leadingIndentOrBlank(chars, below, tabSize) >
                leadingIndentOrBlank(chars, above, tabSize) -> below

            else -> above
        }
    }

    private fun clearHighlight() {
        // Avoid touching the markup model of an editor that the platform has already
        // disposed; the highlighter is gone with it.
        if (!editor.isDisposed) {
            rangeHighlighter?.dispose()
        }
        rangeHighlighter = null
        appliedGuide = null
    }

    private data class BracketOpener(
        val char: Char,
        val offset: Int,
    )

    private data class ScopeInfo(
        val closer: Char?,
        val bodyStartLine: Int,
        val endLine: Int? = null,
    )

    private data class ScopeGuide(
        val anchorLine: Int,
        val endLine: Int,
    )

    private companion object {
        private const val UPDATE_DELAY_MS = 20
    }
}

private class ActiveIndentGuideRenderer(
    private val anchorLine: Int,
    private val endLine: Int,
) : CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, graphics: Graphics) {
        val color = editor.colorsScheme.getColor(EditorColors.SELECTED_INDENT_GUIDE_COLOR)
            ?: editor.colorsScheme.getColor(EditorColors.INDENT_GUIDE_COLOR)
            ?: return

        // The guide starts at the line that opens the scope and ends at its closing
        // bracket (a `}`, `)` or `]` line), so it covers the whole block. The vertical
        // is placed half an indent step to the left of the opener text, inside its
        // leading whitespace, so the rounded arms have room to reach the opener and
        // the closing line. At column 0 there is no leading whitespace, so the guide
        // falls back to the first character of the opener line.
        val chars = editor.document.charsSequence
        val firstAnchor = lineTextAnchor(editor, chars, anchorLine)
        val lastAnchor = lineTextAnchor(editor, chars, endLine)
        val halfLine = editor.lineHeight / 2

        val lineStartX = editor.offsetToXY(editor.document.getLineStartOffset(anchorLine)).x
        val leadingWidth = (firstAnchor.x - lineStartX).coerceAtLeast(0)
        val halfIndentStep = editor.settings.getTabSize(editor.project).coerceAtLeast(2) / 2f *
            graphics.fontMetrics.charWidth(' ').coerceAtLeast(1)
        val armLength = minOf(halfIndentStep, leadingWidth / 2f).toInt()
        val guideX = firstAnchor.x - armLength

        val copy = graphics.create()
        if (copy !is Graphics2D) {
            copy.dispose()
            return
        }

        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            copy.color = color
            copy.stroke = BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            copy.draw(
                createScopePath(
                    guideX = guideX,
                    firstTextX = firstAnchor.x,
                    firstY = firstAnchor.y + halfLine,
                    lastTextX = lastAnchor.x,
                    lastY = lastAnchor.y + halfLine,
                ),
            )
        } finally {
            copy.dispose()
        }
    }

    /**
     * Position of the first non-whitespace character of the line. Using the character
     * offset (instead of the line start) keeps the arm aligned when the text is soft
     * wrapped and starts on a continuation visual line.
     */
    private fun lineTextAnchor(editor: Editor, chars: CharSequence, line: Int): Point {
        val document = editor.document
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)

        var offset = lineStart
        while (offset < lineEnd && (chars[offset] == ' ' || chars[offset] == '\t')) {
            offset++
        }
        return editor.offsetToXY(offset)
    }

    /**
     * Builds the rounded bracket used by editors such as Neovim/snacks:
     * a vertical line with rounded corners that turn towards the first and last
     * lines of the scope.
     */
    private fun createScopePath(
        guideX: Int,
        firstTextX: Int,
        firstY: Int,
        lastTextX: Int,
        lastY: Int,
    ): Path2D.Float {
        val x = guideX.toFloat()
        val topArmEnd = maxOf(firstTextX, guideX + 1).toFloat()
        val bottomArmEnd = maxOf(lastTextX, guideX + 1).toFloat()
        val path = Path2D.Float()

        val radius = minOf(
            CORNER_RADIUS,
            (topArmEnd - x).coerceAtLeast(0f),
            (bottomArmEnd - x).coerceAtLeast(0f),
            (lastY - firstY).toFloat() / 2f,
        ).coerceAtLeast(0f)

        val top = firstY.toFloat()
        val bottom = lastY.toFloat()

        path.moveTo(topArmEnd, top)
        path.lineTo(x + radius, top)
        if (radius > 0f) {
            path.quadTo(x, top, x, top + radius)
            path.lineTo(x, bottom - radius)
            path.quadTo(x, bottom, x + radius, bottom)
        } else {
            path.lineTo(x, bottom)
        }
        path.lineTo(bottomArmEnd, bottom)
        return path
    }

    private companion object {
        private const val CORNER_RADIUS = 4f
        private const val STROKE_WIDTH = 1.5f
    }
}
