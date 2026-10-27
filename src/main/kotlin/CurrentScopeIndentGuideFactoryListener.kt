package com.access

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.geom.Path2D
import javax.swing.SwingUtilities

/**
 * Whether the custom guide should be painted. With [onlyActiveEditor] disabled the
 * guide is visible everywhere; otherwise it follows keyboard focus, which supports
 * split editors without drawing a guide in every pane.
 */
internal fun shouldShowGuide(
    enabled: Boolean,
    onlyActiveEditor: Boolean,
    focusOwner: Component?,
    editorComponent: Component,
): Boolean {
    if (!enabled) return false
    if (!onlyActiveEditor) return true
    return focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, editorComponent)
}

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
    private val settings: IndentScopeSettings
        get() = IndentScopeSettings.getInstance()
    private val focusListener = object : FocusAdapter() {
        override fun focusGained(e: FocusEvent) = onFocusChanged()

        override fun focusLost(e: FocusEvent) = onFocusChanged()
    }

    private var rangeHighlighter: RangeHighlighter? = null
    private var appliedGuide: ScopeGuide? = null
    private var cachedGuide: ScopeGuide? = null
    private var cachedCaretLine = -1
    private var cachedModificationStamp = Long.MIN_VALUE
    private var cachedTabSize = -1
    private var hasCachedGuide = false
    private var savedNativeIndentGuides: Boolean? = null
    private var disposed = false

    init {
        editor.caretModel.addCaretListener(this)
        editor.document.addDocumentListener(this, listenerDisposable)
        editor.contentComponent.addFocusListener(focusListener)
        editor.foldingModel.addListener(
            object : FoldingListener {
                override fun onFoldProcessingEnd() {
                    // Folding changes the visible extent of the guide, not the
                    // logical scope, so the next update only needs to re-evaluate
                    // visibility and the painted path.
                    scheduleUpdate()
                }
            },
            listenerDisposable,
        )
    }

    /**
     * Applies every persisted setting to this editor. The service calls this when
     * the editor is created and whenever Settings → Tools → IndentScope changes.
     */
    fun onSettingsChanged() {
        if (disposed || editor.isDisposed) return

        applyNativeGuidePolicy()
        hasCachedGuide = false

        if (settings.enabled && settings.shouldShowInEditor(editor.editorKind)) {
            updateHighlight()
        } else {
            updateAlarm.cancelAllRequests()
            clearHighlight()
        }
    }

    /**
     * The IDE's global "Show indent guides" setting can change while the custom
     * guide is active. When the plugin is responsible for hiding native guides,
     * capture the user's new preference and apply the policy again. This keeps the
     * displayed state consistent and restores the latest preference on disable.
     */
    fun onExternalIndentGuidesChanged() {
        if (disposed || editor.isDisposed) return
        if (!settings.enabled || !settings.shouldShowInEditor(editor.editorKind)) return
        if (settings.getState().keepNativeGuides) return

        savedNativeIndentGuides = EditorSettingsExternalizable.getInstance().isIndentGuidesShown
        applyNativeGuidePolicy()
    }

    /**
     * Hides native guides only while the custom guide is active and the user did not
     * ask to keep them. The original per-editor value is captured once and restored
     * when the feature is disabled, when native guides are re-enabled, or on dispose.
     */
    private fun applyNativeGuidePolicy() {
        if (editor.isDisposed) return

        val shouldHide = settings.enabled &&
            settings.shouldShowInEditor(editor.editorKind) &&
            !settings.getState().keepNativeGuides
        if (shouldHide) {
            if (savedNativeIndentGuides == null) {
                savedNativeIndentGuides = editor.settings.isIndentGuidesShown
            }
            if (editor.settings.isIndentGuidesShown) {
                editor.settings.isIndentGuidesShown = false
            }
        } else {
            restoreNativeGuides()
        }
    }

    private fun restoreNativeGuides() {
        savedNativeIndentGuides?.let { saved ->
            if (editor.settings.isIndentGuidesShown != saved) {
                editor.settings.isIndentGuidesShown = saved
            }
        }
        savedNativeIndentGuides = null
    }

    private fun onFocusChanged() {
        if (!settings.getState().showOnlyInActiveEditor) return
        // Wait until the focus transfer finishes before deciding which editor owns it.
        scheduleUpdate()
    }

    private fun shouldDrawGuide(): Boolean {
        if (disposed || editor.isDisposed) return false
        if (!settings.shouldShowInEditor(editor.editorKind)) return false
        val state = settings.getState()
        return shouldShowGuide(
            enabled = settings.enabled,
            onlyActiveEditor = state.showOnlyInActiveEditor,
            focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner,
            editorComponent = editor.contentComponent,
        )
    }

    override fun caretPositionChanged(event: CaretEvent) {
        if (!settings.enabled) return
        // Multiple carets still produce one custom guide: the one for the primary
        // caret. Moving a secondary caret cannot change it.
        if (event.caret != editor.caretModel.primaryCaret) return
        // The guide depends on the caret line only, so horizontal moves are free.
        if (event.oldPosition.line == event.newPosition.line) return
        scheduleUpdate()
    }

    override fun caretAdded(event: CaretEvent) {
        if (settings.enabled) scheduleUpdate()
    }

    override fun caretRemoved(event: CaretEvent) {
        if (settings.enabled) scheduleUpdate()
    }

    override fun documentChanged(event: DocumentEvent) {
        if (!settings.enabled) return
        scheduleUpdate()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        updateAlarm.cancelAllRequests()
        if (!editor.isDisposed) {
            editor.caretModel.removeCaretListener(this)
            editor.contentComponent.removeFocusListener(focusListener)
            restoreNativeGuides()
        }
        Disposer.dispose(listenerDisposable)
        clearHighlight()
    }

    /**
     * Coalesces bursts of caret/document events into a single recomputation, which
     * keeps fast typing and rapid navigation cheap on large files.
     */
    private fun scheduleUpdate() {
        if (!settings.enabled || disposed || editor.isDisposed) return
        updateAlarm.cancelRequest(updateRequest)
        updateAlarm.addRequest(updateRequest, UPDATE_DELAY_MS)
    }

    private fun updateHighlight() {
        if (!shouldDrawGuide()) {
            clearHighlight()
            return
        }

        val guide = getCurrentGuide()?.visibleWithFolds(collapsedFoldRanges())
        if (guide == appliedGuide && (guide == null || rangeHighlighter?.isValid == true)) return

        clearHighlight()
        if (guide == null) return

        val state = settings.getState()
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
            strokeWidth = state.strokeWidth.toFloat(),
            cornerRadius = state.cornerRadius.toFloat(),
            customColor = if (state.customColorEnabled) Color(state.customColorRgb) else null,
        )
        rangeHighlighter = highlighter
        appliedGuide = guide
    }

    /**
     * The scan is cached by caret line, document modification stamp and tab size, so
     * repeated updates that cannot change the answer reuse the previous guide.
     */
    private fun getCurrentGuide(): ScopeGuide? {
        val caretLine = editor.caretModel.primaryCaret.logicalPosition.line
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
        val caretLine = editor.caretModel.primaryCaret.logicalPosition.line
        return ScopeDetector(chars, lines, brackets, tabSize, settings.allowedOpeners()).findGuide(caretLine)
    }

    /**
     * Collapsed folds never hide the line where their placeholder is painted, but
     * every following line inside the region is not visible. The guide is hidden
     * when either end of the scope falls into such a hidden portion.
     */
    private fun collapsedFoldRanges(): List<FoldedLineRange> {
        val document = editor.document
        return editor.foldingModel.allFoldRegions.mapNotNull { region ->
            if (!region.isValid || region.isExpanded) {
                null
            } else {
                val start = document.getLineNumber(region.startOffset.coerceIn(0, document.textLength))
                val end = document.getLineNumber(region.endOffset.coerceIn(0, document.textLength))
                FoldedLineRange(start, end)
            }
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

    private companion object {
        private const val UPDATE_DELAY_MS = 20
    }
}

private class ActiveIndentGuideRenderer(
    private val anchorLine: Int,
    private val endLine: Int,
    private val strokeWidth: Float,
    private val cornerRadius: Float,
    private val customColor: Color?,
) : CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, graphics: Graphics) {
        val color = customColor ?: IndentScopeColors.guideColor(editor.colorsScheme) ?: return

        // The guide starts at the line that opens the scope and ends at its closing
        // bracket (a `}`, `)` or `]` line), so it covers the whole block. The vertical
        // is placed half an indent step to the left of the opener text, inside its
        // leading whitespace, so the rounded arms have room to reach the opener and
        // the closing line. At column 0 there is no leading whitespace, so the guide
        // falls back to the first character of the opener line and is drawn as a
        // plain vertical line: a one-pixel arm there looks like a stray dot. The
        // stroke width and radius are logical pixels; the editor's Graphics2D already
        // applies the HiDPI scale, so applying it again here would double the size.
        val chars = editor.document.charsSequence
        val firstAnchor = lineTextAnchor(editor, chars, anchorLine) ?: return
        val lastAnchor = lineTextAnchor(editor, chars, endLine) ?: return
        val lineHeight = editor.lineHeight
        if (lineHeight <= 0) return

        val halfLine = lineHeight / 2
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
            copy.stroke = BasicStroke(
                strokeWidth.coerceAtLeast(MIN_STROKE_WIDTH),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )
            copy.draw(
                createScopePath(
                    guideX = guideX,
                    firstTextX = firstAnchor.x,
                    firstY = firstAnchor.y + halfLine,
                    lastTextX = lastAnchor.x,
                    lastY = lastAnchor.y + halfLine,
                    drawArms = drawArms,
                    cornerRadius = cornerRadius,
                    hiddenYRanges = softWrapBreakRanges(
                        guideX = guideX.toFloat(),
                        wraps = softWrapCrossovers(editor, anchorLine, endLine),
                    ),
                ),
            )
        } finally {
            copy.dispose()
        }
    }

    /**
     * Position of the first non-whitespace character of the line. Using the character
     * offset (instead of the line start) keeps the arm aligned when the line is soft
     * wrapped and its text starts on a continuation visual line. The line visibility
     * is filtered by [visibleWithFolds] before painting; this method only rejects
     * coordinates that the editor cannot map to the visible content.
     */
    private fun lineTextAnchor(editor: Editor, chars: CharSequence, line: Int): Point? {
        val document = editor.document
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)

        var offset = lineStart
        while (offset < lineEnd && (chars[offset] == ' ' || chars[offset] == '\t')) {
            offset++
        }
        val point = editor.offsetToXY(offset)
        return if (point.x < 0 || point.y < 0) null else point
    }

    /**
     * Soft-wrapped continuation lines that start to the left of the guide would have
     * text behind the vertical stroke. Those visual rows are excluded from the path.
     */
    private fun softWrapCrossovers(editor: Editor, anchorLine: Int, endLine: Int): List<SoftWrapCrossover> {
        val document = editor.document
        val start = document.getLineStartOffset(anchorLine)
        val end = document.getLineEndOffset(endLine)
        if (end <= start) return emptyList()

        val model = editor.softWrapModel
        if (!model.isSoftWrappingEnabled) return emptyList()

        return model.getSoftWrapsForRange(start, end).mapNotNull { wrap ->
            if (!model.isVisible(wrap)) {
                null
            } else {
                val visualLine = editor.offsetToVisualLine(wrap.start, false)
                SoftWrapCrossover(
                    topY = editor.visualLineToY(visualLine),
                    lineHeight = editor.lineHeight,
                    indentPixels = wrap.indentInPixels,
                )
            }
        }
    }

    private companion object {
        private const val MIN_STROKE_WIDTH = 0.5f
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
    cornerRadius: Float = DEFAULT_CORNER_RADIUS,
    hiddenYRanges: List<IntRange> = emptyList(),
): Path2D.Float {
    val x = guideX.toFloat()
    val topArmEnd = if (drawArms) maxOf(firstTextX.toFloat(), x) else x
    val bottomArmEnd = if (drawArms) maxOf(lastTextX.toFloat(), x) else x
    val path = Path2D.Float()

    val radius = minOf(
        cornerRadius.coerceAtLeast(0f),
        (topArmEnd - x).coerceAtLeast(0f),
        (bottomArmEnd - x).coerceAtLeast(0f),
        (lastY - firstY).toFloat() / 2f,
    ).coerceAtLeast(0f)

    val top = firstY.toFloat()
    val bottom = lastY.toFloat()
    val segments = visibleVerticalSegments(firstY, lastY, hiddenYRanges)
    if (segments.isEmpty()) return path

    segments.forEachIndexed { index, segment ->
        val isFirst = index == 0
        val isLast = index == segments.lastIndex
        val segmentTop = segment.first.toFloat()
        val segmentBottom = segment.last.toFloat()

        if (isFirst) {
            path.moveTo(topArmEnd, top)
            path.lineTo(x + radius, top)
            if (radius > 0f) {
                path.quadTo(x, top, x, top + radius)
                path.lineTo(x, segmentBottom)
            } else {
                path.lineTo(x, segmentBottom)
            }
        } else {
            path.moveTo(x, segmentTop)
            path.lineTo(x, segmentBottom)
        }

        if (isLast) {
            if (radius > 0f) {
                path.lineTo(x, segmentBottom - radius)
                path.quadTo(x, segmentBottom, x + radius, segmentBottom)
            } else {
                path.lineTo(x, segmentBottom)
            }
            path.lineTo(bottomArmEnd, segmentBottom)
        }
    }

    return path
}

/**
 * Splits the vertical part of the guide around the soft-wrapped rows that contain
 * text to the left of it. The first and last rows are preserved so the rounded arms
 * are not clipped.
 */
private fun visibleVerticalSegments(
    top: Int,
    bottom: Int,
    hiddenYRanges: List<IntRange>,
): List<IntRange> {
    if (bottom < top) return emptyList()

    val blocked = hiddenYRanges.mapNotNull { range ->
        val start = maxOf(range.first, top + 1)
        val end = minOf(range.last, bottom - 1)
        if (start <= end) start..end else null
    }.sortedBy { it.first }

    val result = mutableListOf<IntRange>()
    var cursor = top
    for (range in blocked) {
        if (range.last < cursor) continue
        if (range.first > bottom) break
        if (range.first > cursor) result += cursor..(range.first - 1)
        cursor = maxOf(cursor, range.last + 1)
    }
    if (cursor <= bottom) result += cursor..bottom
    return result
}

private const val DEFAULT_CORNER_RADIUS = 4f
