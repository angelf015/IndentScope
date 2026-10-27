package com.access

import com.intellij.openapi.editor.EditorKind

/**
 * Logical line range of a collapsed fold region. [startLine] stays visible because
 * the editor paints the fold placeholder on it; only the lines after it are hidden.
 */
internal data class FoldedLineRange(
    val startLine: Int,
    val endLine: Int,
)

internal fun isLineHiddenByFold(line: Int, folds: List<FoldedLineRange>): Boolean =
    folds.any { line > it.startLine && line <= it.endLine }

/**
 * A fold that hides the opener or the closing line leaves no meaningful vertical
 * extent for the guide, so it is hidden. Folds whose start line is visible keep the
 * guide, which is useful when only a nested body section is collapsed.
 */
internal fun ScopeGuide.visibleWithFolds(folds: List<FoldedLineRange>): ScopeGuide? {
    if (isLineHiddenByFold(anchorLine, folds) || isLineHiddenByFold(endLine, folds)) return null
    return this
}

/**
 * Main editors and untyped editors are always tracked. Console output, diff views and
 * previews are opt-in because they are usually read-only or short-lived surfaces.
 */
internal fun isEditorKindEnabled(
    kind: EditorKind,
    showInConsoleEditors: Boolean,
    showInDiffEditors: Boolean,
    showInPreviewEditors: Boolean,
): Boolean = when (kind) {
    EditorKind.MAIN_EDITOR, EditorKind.UNTYPED -> true
    EditorKind.CONSOLE -> showInConsoleEditors
    EditorKind.DIFF -> showInDiffEditors
    EditorKind.PREVIEW -> showInPreviewEditors
}

/**
 * Vertical band that must be left blank because a soft-wrapped continuation line
 * starts to the left of the guide. Drawing the vertical line through it would paint
 * over the wrapped text.
 */
internal data class SoftWrapCrossover(
    val topY: Int,
    val lineHeight: Int,
    val indentPixels: Int,
)

internal fun softWrapBreakRanges(
    guideX: Float,
    wraps: List<SoftWrapCrossover>,
): List<IntRange> = wraps
    .filter { it.indentPixels < guideX }
    .map { it.topY until it.topY + it.lineHeight }
    .sortedBy { it.first }
