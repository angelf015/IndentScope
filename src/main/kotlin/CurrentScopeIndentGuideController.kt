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
import com.intellij.openapi.project.Project
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
    private val animationAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, listenerDisposable)
    private val animationTick = Runnable { tickAnimation() }
    private val settings: IndentScopeSettings
        get() = IndentScopeSettings.getInstance()
    private val project: Project?
        get() = editor.project
    private val focusListener = object : FocusAdapter() {
        override fun focusGained(e: FocusEvent) = onFocusChanged()

        override fun focusLost(e: FocusEvent) = onFocusChanged()
    }

    private var paintedGuides: List<PaintedGuide> = emptyList()
    private var fadingGuides: List<PaintedGuide> = emptyList()
    private var animationStartedAt = 0L
    private var cachedGuides: List<ScopeGuide> = emptyList()
    private var cachedGuideLimit = -1
    private var cachedCaretLine = -1
    private var cachedModificationStamp = Long.MIN_VALUE
    private var cachedTabSize = -1
    private var cachedOpeners: Set<Char> = emptySet()
    private var hasCachedGuides = false
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

        finishAnimation()
        applyNativeGuidePolicy()
        hasCachedGuides = false

        val state = settings.stateFor(project)
        if (state.enabled && settings.shouldShowInEditor(editor.editorKind, project)) {
            updateHighlight()
        } else {
            updateAlarm.cancelAllRequests()
            clearHighlights()
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
        val state = settings.stateFor(project)
        if (!state.enabled || !settings.shouldShowInEditor(editor.editorKind, project)) return
        if (state.keepNativeGuides) return

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

        val state = settings.stateFor(project)
        val shouldHide = state.enabled &&
            settings.shouldShowInEditor(editor.editorKind, project) &&
            !state.keepNativeGuides
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
        if (!settings.stateFor(project).showOnlyInActiveEditor) return
        // Wait until the focus transfer finishes before deciding which editor owns it.
        scheduleUpdate()
    }

    private fun shouldDrawGuide(): Boolean {
        if (disposed || editor.isDisposed) return false
        if (!settings.shouldShowInEditor(editor.editorKind, project)) return false
        val state = settings.stateFor(project)
        return shouldShowGuide(
            enabled = state.enabled,
            onlyActiveEditor = state.showOnlyInActiveEditor,
            focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner,
            editorComponent = editor.contentComponent,
        )
    }

    override fun caretPositionChanged(event: CaretEvent) {
        if (!settings.isEnabled(project)) return
        // Multiple carets still produce one custom guide: the one for the primary
        // caret. Moving a secondary caret cannot change it.
        if (event.caret != editor.caretModel.primaryCaret) return
        // The guide depends on the caret line only, so horizontal moves are free.
        if (event.oldPosition.line == event.newPosition.line) return
        scheduleUpdate()
    }

    override fun caretAdded(event: CaretEvent) {
        if (settings.isEnabled(project)) scheduleUpdate()
    }

    override fun caretRemoved(event: CaretEvent) {
        if (settings.isEnabled(project)) scheduleUpdate()
    }

    override fun documentChanged(event: DocumentEvent) {
        if (!settings.isEnabled(project)) return
        // Fading guides reference line numbers from the previous snapshot; settle
        // them before the debounced update rebuilds the current scopes.
        finishAnimation()
        scheduleUpdate()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        updateAlarm.cancelAllRequests()
        animationAlarm.cancelAllRequests()
        if (!editor.isDisposed) {
            editor.caretModel.removeCaretListener(this)
            editor.contentComponent.removeFocusListener(focusListener)
            restoreNativeGuides()
        }
        clearHighlights()
        Disposer.dispose(listenerDisposable)
        cachedAnalysis = null
        cachedGuides = emptyList()
        hasCachedGuides = false
    }

    /**
     * Coalesces bursts of caret/document events into a single recomputation, which
     * keeps fast typing and rapid navigation cheap on large files.
     */
    private fun scheduleUpdate() {
        if (!settings.isEnabled(project) || disposed || editor.isDisposed) return
        updateAlarm.cancelRequest(updateRequest)
        updateAlarm.addRequest(updateRequest, UPDATE_DELAY_MS)
    }

    private fun updateHighlight() {
        if (!shouldDrawGuide()) {
            clearHighlights()
            return
        }

        val state = settings.stateFor(project)
        val folds = collapsedFoldRanges()
        val guides = getCurrentGuides(state)
            .mapNotNull { guide ->
                val visible = guide.visibleWithFolds(folds) ?: return@mapNotNull null
                // Column-0 guides are opt-in: at the file margin they are visually
                // intrusive, so the default behavior is to leave those scopes unmarked.
                if (visible.columnZero && !state.showColumnZeroGuide) return@mapNotNull null
                visible
            }
            .mapIndexed { depth, guide -> GuideSpec(guide, depth) }
        if (guides.isEmpty()) {
            clearHighlights()
            return
        }

        val sameGuides = guides.size == paintedGuides.size &&
            guides.indices.all { index ->
                guides[index].guide == paintedGuides[index].guide &&
                    guides[index].depth == paintedGuides[index].depth &&
                    paintedGuides[index].highlighter.isValid
            }
        if (sameGuides) {
            // The ranges did not change: keep the highlighters and only refresh the
            // renderers so color, stroke, radius and fade settings apply immediately.
            finishAnimation()
            return
        }

        val canAnimate = state.animateScopeChanges &&
            paintedGuides.isNotEmpty() &&
            paintedGuides.all { it.highlighter.isValid }
        if (!canAnimate) {
            clearHighlights()
            paintedGuides = guides.map { createPaintedGuide(it, state, progress = 1f) }
            return
        }

        // Keep the previous frame as the fade-out layer, then paint the new scopes
        // from zero opacity. Any transition still running is settled first.
        finishAnimation()
        fadingGuides = paintedGuides
        paintedGuides = guides.map { createPaintedGuide(it, state, progress = 0f) }
        animationStartedAt = System.nanoTime()
        animationAlarm.addRequest(animationTick, 0)
    }

    private fun createPaintedGuide(
        spec: GuideSpec,
        state: IndentScopeSettings.State,
        progress: Float,
    ): PaintedGuide {
        val document = editor.document
        val highlighter = editor.markupModel.addRangeHighlighter(
            document.getLineStartOffset(spec.guide.anchorLine),
            document.getLineEndOffset(spec.guide.endLine),
            HighlighterLayer.CARET_ROW + 1,
            TextAttributes(),
            HighlighterTargetArea.EXACT_RANGE,
        )
        highlighter.customRenderer = createRenderer(spec.guide, state, spec.depth, progress)
        return PaintedGuide(spec.guide, spec.depth, highlighter)
    }

    private fun refreshRenderer(
        painted: PaintedGuide,
        state: IndentScopeSettings.State,
        progress: Float,
    ) {
        if (painted.highlighter.isValid) {
            painted.highlighter.customRenderer = createRenderer(painted.guide, state, painted.depth, progress)
        }
    }

    private fun createRenderer(
        guide: ScopeGuide,
        state: IndentScopeSettings.State,
        depth: Int,
        progress: Float,
    ): ActiveIndentGuideRenderer = ActiveIndentGuideRenderer(
        anchorLine = guide.anchorLine,
        endLine = guide.endLine,
        strokeWidth = state.strokeWidth.toFloat(),
        cornerRadius = state.cornerRadius.toFloat(),
        columnZero = guide.columnZero,
        endsAtClosingLine = guide.endsAtClosingLine,
        customColor = if (state.customColorEnabled) java.awt.Color(state.customColorRgb) else null,
        alpha = scopeGuideAlpha(depth) * progress.coerceIn(0f, 1f),
    )

    /**
     * Advances the optional fade between the previous and current scope frames.
     * The ticker only updates renderers and repaints the editor; it never rescans
     * the document.
     */
    private fun tickAnimation() {
        if (disposed || editor.isDisposed) {
            finishAnimation()
            return
        }

        val elapsedMs = (System.nanoTime() - animationStartedAt) / NANOS_PER_MILLI
        val progress = (elapsedMs / SCOPE_FADE_DURATION_MS).toFloat().coerceIn(0f, 1f)
        val state = settings.stateFor(project)
        fadingGuides.forEach { refreshRenderer(it, state, progress = 1f - progress) }
        paintedGuides.forEach { refreshRenderer(it, state, progress = progress) }
        editor.contentComponent.repaint()

        if (progress < 1f) {
            animationAlarm.addRequest(animationTick, ANIMATION_FRAME_MS)
        } else {
            finishAnimation()
        }
    }

    /**
     * Settles the fade immediately: old highlighters are removed, the current ones
     * return to their configured opacity and any pending tick is cancelled.
     */
    private fun finishAnimation() {
        animationAlarm.cancelRequest(animationTick)
        if (!editor.isDisposed) {
            fadingGuides.forEach { it.highlighter.dispose() }
        }
        fadingGuides = emptyList()
        if (!editor.isDisposed && paintedGuides.isNotEmpty()) {
            val state = settings.stateFor(project)
            paintedGuides.forEach { refreshRenderer(it, state, progress = 1f) }
        }
    }

    /**
     * The guide cache is keyed by everything that can change the answer. The
     * analysis cache below additionally reuses the bracket index across caret
     * movements on the same document snapshot.
     */
    private fun getCurrentGuides(state: IndentScopeSettings.State): List<ScopeGuide> {
        val caretLine = editor.caretModel.primaryCaret.logicalPosition.line
        val stamp = editor.document.modificationStamp
        val tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
        val openers = settings.allowedOpeners(project)
        val limit = if (state.showEnclosingScopes) state.maxEnclosingScopes else 1
        if (hasCachedGuides &&
            caretLine == cachedCaretLine &&
            stamp == cachedModificationStamp &&
            tabSize == cachedTabSize &&
            openers == cachedOpeners &&
            limit == cachedGuideLimit
        ) {
            return cachedGuides
        }

        val guides = getAnalysis(tabSize, openers).detector.findGuides(caretLine, limit)
        cachedGuides = guides
        cachedCaretLine = caretLine
        cachedModificationStamp = stamp
        cachedTabSize = tabSize
        cachedOpeners = openers
        cachedGuideLimit = limit
        hasCachedGuides = true
        return guides
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

    private fun clearHighlights() {
        animationAlarm.cancelRequest(animationTick)
        // Avoid touching the markup model of an editor that the platform has already
        // disposed; the highlighters are gone with it.
        if (!editor.isDisposed) {
            paintedGuides.forEach { it.highlighter.dispose() }
            fadingGuides.forEach { it.highlighter.dispose() }
        }
        paintedGuides = emptyList()
        fadingGuides = emptyList()
    }

    private data class GuideSpec(
        val guide: ScopeGuide,
        val depth: Int,
    )

    private data class PaintedGuide(
        val guide: ScopeGuide,
        val depth: Int,
        val highlighter: RangeHighlighter,
    )

    private companion object {
        private const val UPDATE_DELAY_MS = 20
        private const val ANIMATION_FRAME_MS = 16
        private const val SCOPE_FADE_DURATION_MS = 150.0
        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}
