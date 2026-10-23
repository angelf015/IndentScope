package com.access

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.util.Disposer
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.geom.Path2D

class CurrentScopeIndentGuideFactoryListener : EditorFactoryListener {
    private val controllers = mutableMapOf<EditorEx, CurrentScopeIndentGuideController>()

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorEx ?: return
        if (editor.isOneLineMode) return

        controllers[editor] = CurrentScopeIndentGuideController(editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorEx ?: return
        controllers.remove(editor)?.dispose()
    }
}

private class CurrentScopeIndentGuideController(
    private val editor: EditorEx,
) : CaretListener, DocumentListener {

    private val originalIndentGuidesShown = editor.settings.isIndentGuidesShown
    private val listenerDisposable: Disposable = Disposer.newDisposable()
    private var rangeHighlighter: RangeHighlighter? = null
    private var disposed = false

    init {
        editor.settings.isIndentGuidesShown = false
        editor.caretModel.addCaretListener(this)
        editor.document.addDocumentListener(this, listenerDisposable)
        updateHighlight()
    }

    override fun caretPositionChanged(event: CaretEvent) {
        updateHighlight()
    }

    override fun documentChanged(event: DocumentEvent) {
        updateHighlight()
    }

    fun dispose() {
        if (disposed) return
        disposed = true

        editor.caretModel.removeCaretListener(this)
        Disposer.dispose(listenerDisposable)
        clearHighlight()
        editor.settings.isIndentGuidesShown = originalIndentGuidesShown
    }

    private fun updateHighlight() {
        if (disposed || editor.isDisposed) return

        clearHighlight()
        val guide = computeCurrentScopeGuide() ?: return
        val startOffset = editor.document.getLineStartOffset(guide.anchorLine)
        val endOffset = editor.document.getLineEndOffset(guide.endLine)

        val highlighter = editor.markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.CARET_ROW + 1,
            TextAttributes(),
            HighlighterTargetArea.EXACT_RANGE,
        )
        highlighter.customRenderer = ActiveIndentGuideRenderer(
            anchorLine = guide.anchorLine,
            endLine = guide.endLine,
        )
        rangeHighlighter = highlighter
    }

    private fun computeCurrentScopeGuide(): ScopeGuide? {
        val document = editor.document
        val lineCount = document.lineCount
        if (lineCount == 0) return null

        val caretLine = editor.caretModel.logicalPosition.line.coerceIn(0, lineCount - 1)
        val baseLine = findNearestNonBlankLine(caretLine, lineCount) ?: return null
        val baseIndent = leadingIndentColumns(baseLine)
        if (baseIndent <= 0) return null

        // The guide belongs to the innermost block containing the caret. That block is
        // opened either by the base line itself (e.g. when the caret is on the `if`
        // line) or by the nearest non-blank line above it with a smaller indentation.
        // Anchoring the guide to that line is what IntelliJ does for regular indent
        // guides, instead of drawing it next to the block contents.
        val openerLine = findScopeOpenerLine(baseLine, baseIndent, lineCount) ?: return null
        val indentColumn = leadingIndentColumns(openerLine)
        if (indentColumn <= 0) return null

        var lastBodyLine = -1
        var closingBraceLine = -1
        val openerHasBlockBrace = opensBraceBlock(openerLine)
        for (line in openerLine + 1 until lineCount) {
            if (isBlankLine(line)) continue

            if (leadingIndentColumns(line) <= indentColumn) {
                // The block ends at its closing brace, which is usually aligned with
                // the line that opens it. Include that line so the guide covers the
                // whole block instead of only the body.
                if (openerHasBlockBrace && firstNonWhitespaceChar(line) == '}') {
                    closingBraceLine = line
                }
                break
            }

            lastBodyLine = line
        }

        if (lastBodyLine < 0) return null

        val endLine = if (closingBraceLine >= 0) closingBraceLine else lastBodyLine
        return ScopeGuide(openerLine, endLine)
    }

    private fun firstNonWhitespaceChar(line: Int): Char? {
        val document = editor.document
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.charsSequence

        for (i in start until end) {
            if (!text[i].isWhitespace()) return text[i]
        }
        return null
    }

    /**
     * Tells whether the line leaves a block open (its last significant brace is `{`).
     * This keeps unbraced scopes from adopting the closing brace of an outer block.
     */
    private fun opensBraceBlock(line: Int): Boolean {
        val document = editor.document
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.charsSequence

        for (i in end - 1 downTo start) {
            when (text[i]) {
                '}' -> return false
                '{' -> return true
            }
        }
        return false
    }

    private fun findScopeOpenerLine(baseLine: Int, baseIndent: Int, lineCount: Int): Int? {
        if (opensBlock(baseLine, baseIndent, lineCount)) return baseLine

        for (line in baseLine - 1 downTo 0) {
            if (isBlankLine(line)) continue
            if (leadingIndentColumns(line) < baseIndent) return line
        }
        return null
    }

    private fun opensBlock(line: Int, indent: Int, lineCount: Int): Boolean {
        for (nextLine in line + 1 until lineCount) {
            if (isBlankLine(nextLine)) continue
            return leadingIndentColumns(nextLine) > indent
        }
        return false
    }

    private fun findNearestNonBlankLine(caretLine: Int, lineCount: Int): Int? {
        if (!isBlankLine(caretLine)) return caretLine

        var above: Int? = null
        for (line in caretLine - 1 downTo 0) {
            if (!isBlankLine(line)) {
                above = line
                break
            }
        }
        var below: Int? = null
        for (line in caretLine + 1 until lineCount) {
            if (!isBlankLine(line)) {
                below = line
                break
            }
        }

        return when {
            above == null -> below
            below == null -> above
            // A blank line between an opener and its body belongs to the body, and so
            // does one between the body and the line that closes the block.
            leadingIndentColumns(below) > leadingIndentColumns(above) -> below
            else -> above
        }
    }

    private fun isBlankLine(line: Int): Boolean {
        val document = editor.document
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.charsSequence

        for (i in start until end) {
            if (!text[i].isWhitespace()) {
                return false
            }
        }
        return true
    }

    private fun leadingIndentColumns(line: Int): Int {
        val document = editor.document
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.charsSequence
        val tabSize = editor.settings.getTabSize(editor.project)

        var columns = 0
        for (i in start until end) {
            when (val ch = text[i]) {
                ' ' -> columns += 1
                '\t' -> {
                    val remainder = columns % tabSize
                    columns += if (remainder == 0) tabSize else tabSize - remainder
                }

                else -> return columns
            }
        }

        return columns
    }

    private fun clearHighlight() {
        rangeHighlighter?.dispose()
        rangeHighlighter = null
    }

    private data class ScopeGuide(
        val anchorLine: Int,
        val endLine: Int,
    )
}

private class ActiveIndentGuideRenderer(
    private val anchorLine: Int,
    private val endLine: Int,
) : CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, graphics: Graphics) {
        val color = editor.colorsScheme.getColor(EditorColors.SELECTED_INDENT_GUIDE_COLOR)
            ?: editor.colorsScheme.getColor(EditorColors.INDENT_GUIDE_COLOR)
            ?: return

        // The guide starts at the line that opens the scope (the `if` line) and ends
        // at its closing brace, so it covers the whole block. The vertical is placed
        // half an indent step to the left of the opener text, inside its leading
        // whitespace, so the rounded arms have room to reach the `if` and the `}`.
        val firstAnchor = lineTextAnchor(editor, anchorLine)
        val lastAnchor = lineTextAnchor(editor, endLine)
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
    private fun lineTextAnchor(editor: Editor, line: Int): Point {
        val document = editor.document
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val chars = document.charsSequence

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
