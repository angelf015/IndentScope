package com.access

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
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
 * Per-editor lifecycle and state for the current-scope guide.
 *
 * All public methods are expected to run on the EDT. [IndentScopeService] enforces
 * that before touching the controller map or the editor markup model.
 */
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
    private var cachedOpeners: Set<Char> = emptySet()
    private var hasCachedGuide = false
    private var cachedAnalysis: ScopeAnalysis? = null
    private var cachedAnalysisStamp = Long.MIN_VALUE
    private var cachedAnalysisTabSize = -1
    private var cachedAnalysisOpeners: Set<Char> = emptySet()
    private var cachedAnalysisFileKey: String? = null
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
        cachedAnalysis = null
        cachedGuide = null
        hasCachedGuide = false
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
        if (guide == null) {
            clearHighlight()
            return
        }

        val state = settings.getState()
        if (guide.columnZero && !state.showColumnZeroGuide) {
            // Column-0 guides are opt-in: at the file margin they are visually
            // intrusive, so the default behavior is to leave those scopes unmarked.
            clearHighlight()
            return
        }
        val existing = rangeHighlighter
        if (guide == appliedGuide && existing?.isValid == true) {
            // The range did not change: keep the highlighter and only refresh the
            // renderer so color, stroke and radius changes apply immediately.
            existing.customRenderer = createRenderer(guide, state)
            return
        }

        clearHighlight()
        val document = editor.document
        val highlighter = editor.markupModel.addRangeHighlighter(
            document.getLineStartOffset(guide.anchorLine),
            document.getLineEndOffset(guide.endLine),
            HighlighterLayer.CARET_ROW + 1,
            TextAttributes(),
            HighlighterTargetArea.EXACT_RANGE,
        )
        highlighter.customRenderer = createRenderer(guide, state)
        rangeHighlighter = highlighter
        appliedGuide = guide
    }

    private fun createRenderer(
        guide: ScopeGuide,
        state: IndentScopeSettings.State,
    ): ActiveIndentGuideRenderer = ActiveIndentGuideRenderer(
        anchorLine = guide.anchorLine,
        endLine = guide.endLine,
        strokeWidth = state.strokeWidth.toFloat(),
        cornerRadius = state.cornerRadius.toFloat(),
        columnZero = guide.columnZero,
        endsAtClosingLine = guide.endsAtClosingLine,
        customColor = if (state.customColorEnabled) java.awt.Color(state.customColorRgb) else null,
    )

    /**
     * The guide cache is keyed by everything that can change the answer. The
     * analysis cache below additionally reuses the bracket index across caret
     * movements on the same document snapshot.
     */
    private fun getCurrentGuide(): ScopeGuide? {
        val caretLine = editor.caretModel.primaryCaret.logicalPosition.line
        val stamp = editor.document.modificationStamp
        val tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
        val openers = settings.allowedOpeners()
        if (hasCachedGuide &&
            caretLine == cachedCaretLine &&
            stamp == cachedModificationStamp &&
            tabSize == cachedTabSize &&
            openers == cachedOpeners
        ) {
            return cachedGuide
        }

        val guide = getAnalysis(tabSize, openers).detector.findGuide(caretLine)
        cachedGuide = guide
        cachedCaretLine = caretLine
        cachedModificationStamp = stamp
        cachedTabSize = tabSize
        cachedOpeners = openers
        hasCachedGuide = true
        return guide
    }

    private fun getAnalysis(tabSize: Int, openers: Set<Char>): ScopeAnalysis {
        val document = editor.document
        val stamp = document.modificationStamp
        val fileKey = editor.virtualFile?.let { "${it.fileType.name}:${it.name}" }
        val cached = cachedAnalysis
        if (cached != null &&
            stamp == cachedAnalysisStamp &&
            tabSize == cachedAnalysisTabSize &&
            openers == cachedAnalysisOpeners &&
            fileKey == cachedAnalysisFileKey
        ) {
            return cached
        }

        val analysis = ScopeAnalysis.create(editor, tabSize, openers)
        cachedAnalysis = analysis
        cachedAnalysisStamp = stamp
        cachedAnalysisTabSize = tabSize
        cachedAnalysisOpeners = openers
        cachedAnalysisFileKey = fileKey
        return analysis
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
