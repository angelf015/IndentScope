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
        if (document.lineCount == 0) return null

        val chars = document.charsSequence
        val lines = DocumentLineMap(document)
        val brackets = BracketScanner(editor, chars, lines)
        val caretLine = editor.caretModel.logicalPosition.line
        return ScopeDetector(chars, lines, brackets, tabSize).findGuide(caretLine)
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
        // falls back to the first character of the opener line and is drawn as a
        // plain vertical line: a one-pixel arm there looks like a stray dot.
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
        val drawArms = armLength > 0

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
                    drawArms = drawArms,
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

    private companion object {
        private const val STROKE_WIDTH = 1.5f
    }
}

/**
 * Builds the path of the current-scope guide. With [drawArms] enabled it is the
 * rounded bracket used by editors such as Neovim/snacks: a vertical line with
 * rounded corners that turn towards the first and last lines of the scope. When
 * the opener starts at column 0 there is no space for those arms, so [drawArms] is
 * false and the path is a plain vertical line without the tiny end hooks that would
 * otherwise look like stray dots.
 */
internal fun createScopePath(
    guideX: Int,
    firstTextX: Int,
    firstY: Int,
    lastTextX: Int,
    lastY: Int,
    drawArms: Boolean,
): Path2D.Float {
    val x = guideX.toFloat()
    val topArmEnd = if (drawArms) maxOf(firstTextX.toFloat(), x) else x
    val bottomArmEnd = if (drawArms) maxOf(lastTextX.toFloat(), x) else x
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

private const val CORNER_RADIUS = 4f
