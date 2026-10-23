package com.access

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
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
        val startOffset = editor.document.getLineStartOffset(guide.startLine)
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
        val indentColumn = leadingIndentColumns(baseLine)
        if (indentColumn <= 0) return null

        var startLine = baseLine
        for (line in baseLine - 1 downTo 0) {
            if (isBlankLine(line)) {
                startLine = line
                continue
            }

            if (leadingIndentColumns(line) < indentColumn) {
                break
            }
            startLine = line
        }

        var endLine = baseLine
        for (line in baseLine + 1 until lineCount) {
            if (isBlankLine(line)) {
                endLine = line
                continue
            }

            if (leadingIndentColumns(line) < indentColumn) {
                break
            }
            endLine = line
        }

        if (startLine > endLine) return null
        return ScopeGuide(indentColumn, startLine, endLine)
    }

    private fun findNearestNonBlankLine(caretLine: Int, lineCount: Int): Int? {
        if (!isBlankLine(caretLine)) return caretLine

        for (line in caretLine - 1 downTo 0) {
            if (!isBlankLine(line)) return line
        }
        for (line in caretLine + 1 until lineCount) {
            if (!isBlankLine(line)) return line
        }
        return null
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
        val startLine: Int,
        val endLine: Int,
    )
}

private class ActiveIndentGuideRenderer(
    private val indentColumn: Int,
    private val startLine: Int,
    private val endLine: Int,
) : CustomHighlighterRenderer {
    override fun paint(editor: com.intellij.openapi.editor.Editor, highlighter: RangeHighlighter, graphics: Graphics) {
        val color = editor.colorsScheme.getColor(EditorColors.INDENT_GUIDE_COLOR) ?: return
        val startPoint = editor.logicalPositionToXY(LogicalPosition(startLine, indentColumn))
        val endPoint = editor.logicalPositionToXY(LogicalPosition(endLine, indentColumn))

        graphics.color = color
        graphics.drawLine(startPoint.x, startPoint.y, startPoint.x, endPoint.y + editor.lineHeight)
    }
}
