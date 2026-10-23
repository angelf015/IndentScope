package com.access

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.util.Disposer
import java.awt.Graphics

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
            indentColumn = guide.indentColumn,
            anchorLine = guide.anchorLine,
            startLine = guide.startLine,
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

        val startLine = openerLine + 1
        var endLine = lineCount - 1
        for (line in startLine until lineCount) {
            if (isBlankLine(line)) continue

            if (leadingIndentColumns(line) <= indentColumn) {
                endLine = line - 1
                break
            }
        }

        if (startLine > endLine) return null
        return ScopeGuide(indentColumn, openerLine, startLine, endLine)
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
        val indentColumn: Int,
        val anchorLine: Int,
        val startLine: Int,
        val endLine: Int,
    )
}

private class ActiveIndentGuideRenderer(
    private val indentColumn: Int,
    private val anchorLine: Int,
    private val startLine: Int,
    private val endLine: Int,
) : CustomHighlighterRenderer {
    override fun paint(editor: com.intellij.openapi.editor.Editor, highlighter: RangeHighlighter, graphics: Graphics) {
        val color = editor.colorsScheme.getColor(EditorColors.INDENT_GUIDE_COLOR) ?: return

        // The x coordinate is taken from the line that opens the scope (the `if` line),
        // so the guide is aligned with it instead of with the block contents.
        val anchor = editor.logicalPositionToXY(LogicalPosition(anchorLine, indentColumn))
        val startY = editor.logicalPositionToXY(LogicalPosition(startLine, indentColumn)).y
        val endY = editor.logicalPositionToXY(LogicalPosition(endLine, indentColumn)).y + editor.lineHeight

        graphics.color = color
        graphics.drawLine(anchor.x, startY, anchor.x, endY)
    }
}
