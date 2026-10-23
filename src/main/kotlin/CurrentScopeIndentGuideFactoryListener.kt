package com.access

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.geom.Path2D

class CurrentScopeIndentGuideFactoryListener : EditorFactoryListener, Disposable {
    private val controllers = mutableMapOf<EditorEx, CurrentScopeIndentGuideController>()
    private var disposed = false

    override fun editorCreated(event: EditorFactoryEvent) {
        if (disposed) return

        val editor = event.editor as? EditorEx ?: return
        if (editor.isOneLineMode) return
        if (controllers.containsKey(editor)) return

        controllers[editor] = CurrentScopeIndentGuideController(editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorEx ?: return
        controllers.remove(editor)?.dispose()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        controllers.values.forEach { it.dispose() }
        controllers.clear()
    }
}

private class CurrentScopeIndentGuideController(
    private val editor: EditorEx,
) : CaretListener, DocumentListener, Disposable {

    // `EditorSettings` is created per editor, so this controller owns the override for
    // its own editor only. Restoring it here cannot affect other open editors.
    private val originalIndentGuidesShown = editor.settings.isIndentGuidesShown
    private val listenerDisposable: Disposable = Disposer.newDisposable()
    private val updateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, listenerDisposable)
    private val updateRequest = Runnable { updateHighlight() }

    private var rangeHighlighter: RangeHighlighter? = null
    private var appliedGuide: ScopeGuide? = null
    private var cachedGuide: ScopeGuide? = null
    private var cachedCaretLine = -1
    private var cachedModificationStamp = Long.MIN_VALUE
    private var hasCachedGuide = false
    private var disposed = false

    init {
        editor.settings.isIndentGuidesShown = false
        editor.caretModel.addCaretListener(this)
        editor.document.addDocumentListener(this, listenerDisposable)
        updateHighlight()
    }

    override fun caretPositionChanged(event: CaretEvent) {
        // The guide depends on the caret line only, so horizontal moves are free.
        if (event.oldPosition.line == event.newPosition.line) return
        scheduleUpdate()
    }

    override fun documentChanged(event: DocumentEvent) {
        scheduleUpdate()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        updateAlarm.cancelAllRequests()
        if (!editor.isDisposed) {
            editor.caretModel.removeCaretListener(this)
            editor.settings.isIndentGuidesShown = originalIndentGuidesShown
        }
        Disposer.dispose(listenerDisposable)
        clearHighlight()
    }

    /**
     * Coalesces bursts of caret/document events into a single recomputation, which
     * keeps fast typing and rapid navigation cheap on large files.
     */
    private fun scheduleUpdate() {
        if (disposed || editor.isDisposed) return
        updateAlarm.cancelRequest(updateRequest)
        updateAlarm.addRequest(updateRequest, UPDATE_DELAY_MS)
    }

    private fun updateHighlight() {
        if (disposed || editor.isDisposed) return

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
     * The scan is cached by caret line and document modification stamp, so repeated
     * updates that cannot change the answer reuse the previous guide.
     */
    private fun getCurrentGuide(): ScopeGuide? {
        val caretLine = editor.caretModel.logicalPosition.line
        val stamp = editor.document.modificationStamp
        if (hasCachedGuide && caretLine == cachedCaretLine && stamp == cachedModificationStamp) {
            return cachedGuide
        }

        val guide = computeCurrentScopeGuide()
        cachedGuide = guide
        cachedCaretLine = caretLine
        cachedModificationStamp = stamp
        hasCachedGuide = true
        return guide
    }

    private fun computeCurrentScopeGuide(): ScopeGuide? {
        val document = editor.document
        val lineCount = document.lineCount
        if (lineCount == 0) return null

        val chars = document.charsSequence
        val tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)

        val caretLine = editor.caretModel.logicalPosition.line.coerceIn(0, lineCount - 1)
        val baseLine = findNearestNonBlankLine(chars, caretLine, lineCount, tabSize) ?: return null
        val baseIndent = leadingIndentOrBlank(chars, baseLine, tabSize)
        if (baseIndent <= 0) return null

        // The guide belongs to the innermost block containing the caret. That block is
        // opened either by the base line itself (e.g. when the caret is on the `if`
        // line) or by the nearest non-blank line above it with a smaller indentation.
        // Anchoring the guide to that line is what IntelliJ does for regular indent
        // guides, instead of drawing it next to the block contents.
        val openerLine = findScopeOpenerLine(chars, baseLine, baseIndent, lineCount, tabSize) ?: return null
        val indentColumn = leadingIndentOrBlank(chars, openerLine, tabSize)
        if (indentColumn <= 0) return null

        var lastBodyLine = -1
        var closingLine = -1
        val closer = expectedCloser(chars, openerLine)
        for (line in openerLine + 1 until lineCount) {
            val indent = leadingIndentOrBlank(chars, line, tabSize)
            if (indent < 0) continue

            if (indent <= indentColumn) {
                // The block ends at its closing bracket, which is usually aligned
                // with the line that opens it. Include that line so the guide covers
                // the whole block instead of only the body. This applies to braces
                // as well as multi-line parentheses and brackets.
                if (closer != null && firstSignificantChar(chars, line) == closer) {
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
     * Closing bracket expected for the last unmatched `(`, `[` or `{` on the line, or
     * null when the line leaves no block open. This is what lets the guide cover
     * multi-line parentheses and brackets, not just braces.
     */
    private fun expectedCloser(chars: CharSequence, line: Int): Char? {
        val document = editor.document
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)

        val openers = StringBuilder()
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
                '(', '[', '{' -> openers.append(ch)
                ')', ']', '}' -> {
                    val opener = when (ch) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    if (openers.isNotEmpty() && openers[openers.length - 1] == opener) {
                        openers.setLength(openers.length - 1)
                    }
                }
            }
            i++
        }

        return when {
            openers.isEmpty() -> null
            openers[openers.length - 1] == '(' -> ')'
            openers[openers.length - 1] == '[' -> ']'
            else -> '}'
        }
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
        rangeHighlighter?.dispose()
        rangeHighlighter = null
        appliedGuide = null
    }

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
        // the closing line.
        val chars = editor.document.charsSequence
        val firstAnchor = lineTextAnchor(editor, chars, anchorLine)
        val lastAnchor = lineTextAnchor(editor, chars, endLine)
        val halfLine = editor.lineHeight / 2

        val lineStartX = editor.offsetToXY(editor.document.getLineStartOffset(anchorLine))?.x
            ?: firstAnchor.x
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
        return editor.offsetToXY(offset) ?: editor.logicalPositionToXY(LogicalPosition(line, 0))
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
