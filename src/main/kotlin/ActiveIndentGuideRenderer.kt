package com.access

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.geom.Path2D

/**
 * Paints the current-scope guide. The renderer is stateless with respect to the
 * editor: it reads the document and soft-wrap model at paint time, so a single
 * highlighter can be reused while the scope range stays the same.
 */
internal class ActiveIndentGuideRenderer(
    private val anchorLine: Int,
    private val endLine: Int,
    private val strokeWidth: Float,
    private val cornerRadius: Float,
    private val columnZero: Boolean,
    private val endsAtClosingLine: Boolean,
    internal val customColor: Color?,
    internal val alpha: Float = 1f,
) : CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, graphics: Graphics) {
        val document = editor.document
        val lineCount = document.lineCount
        // A document edit can invalidate a fade-out frame before the debounced
        // update removes it. Skip stale line numbers instead of throwing.
        if (anchorLine !in 0 until lineCount || endLine !in 0 until lineCount || endLine < anchorLine) return

        val baseColor = customColor ?: IndentScopeColors.guideColor(editor.colorsScheme) ?: return
        val color = applyGuideAlpha(baseColor, alpha)
        if (color.alpha == 0) return

        // The guide spans the scope: it starts on the line that opens it and ends at
        // the closing bracket (a `}`, `)` or `]` line). Indented guides sit half an
        // indent step to the left of the opener text, inside its leading whitespace,
        // so the rounded arms have room to reach the opener and the closing line. At
        // column 0 there is no leading whitespace, so the guide falls back to the
        // first character and is drawn as a plain vertical line without arms: a
        // one-pixel arm there would look like a stray dot. The stroke width and radius
        // are logical pixels; the editor's Graphics2D already applies the HiDPI scale,
        // so applying it again here would double the size.
        val chars = document.charsSequence
        val firstAnchor = lineTextAnchor(editor, chars, anchorLine) ?: return
        val lastAnchor = lineTextAnchor(editor, chars, endLine) ?: return
        val lineHeight = editor.lineHeight
        if (lineHeight <= 0) return

        val lineStartX = editor.offsetToXY(document.getLineStartOffset(anchorLine)).x
        val leadingWidth = (firstAnchor.x - lineStartX).coerceAtLeast(0)
        val halfIndentStep = editor.settings.getTabSize(editor.project).coerceAtLeast(2) / 2f *
            graphics.fontMetrics.charWidth(' ').coerceAtLeast(1)
        val armLength = minOf(halfIndentStep, leadingWidth / 2f).toInt()
        val guideX = firstAnchor.x - armLength
        val drawArms = !columnZero && armLength > 0

        // Armed guides join the middle of the opener and closing lines. Column-0
        // guides have no arms, so they run from below the first logical line to above
        // the last one: above the closing bracket when there is one, or to the bottom
        // of the last body line for unbraced scopes such as Python's `def`.
        val firstLineBottomY = if (columnZero) {
            val anchorEnd = document.getLineEndOffset(anchorLine)
            editor.visualLineToY(editor.offsetToVisualLine(anchorEnd, true)) + lineHeight
        } else {
            firstAnchor.y + lineHeight
        }
        val lastLineTopY = if (columnZero) {
            val endStart = document.getLineStartOffset(endLine)
            editor.visualLineToY(editor.offsetToVisualLine(endStart, false))
        } else {
            lastAnchor.y
        }
        val lastLineBottomY = lastLineTopY + lineHeight
        val verticalExtent = guideVerticalExtent(
            firstLineTopY = firstAnchor.y,
            firstLineBottomY = firstLineBottomY,
            lastLineTopY = lastLineTopY,
            lastLineBottomY = lastLineBottomY,
            joinLineCenters = !columnZero,
            endsAtClosingLine = endsAtClosingLine,
        ) ?: return

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
                if (drawArms) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_ROUND,
            )
            if (columnZero) {
                // Antialiased butt caps can bleed one device row past the path
                // endpoint. Clip the stroke to the exact band between the opener and
                // the last line so the guide never touches either of them. Unbraced
                // scopes include their last body line, so the band reaches its bottom.
                val clipBottom = if (endsAtClosingLine) lastLineTopY else lastLineBottomY
                copy.clipRect(
                    0,
                    firstLineBottomY,
                    maxOf(editor.contentComponent.width, guideX + 1),
                    clipBottom - firstLineBottomY,
                )
            }
            copy.draw(
                createScopePath(
                    guideX = guideX,
                    firstTextX = firstAnchor.x,
                    firstY = verticalExtent.first,
                    lastTextX = lastAnchor.x,
                    lastY = verticalExtent.last,
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
 * Applies a fade level to [color] while preserving the color's own alpha. A fully
 * opaque level returns the original instance, which avoids allocations in the common
 * single-guide case.
 */
internal fun applyGuideAlpha(color: Color, alpha: Float): Color {
    val level = alpha.coerceIn(0f, 1f)
    if (level >= 1f) return color
    return Color(
        color.red,
        color.green,
        color.blue,
        (color.alpha * level).toInt().coerceIn(0, 255),
    )
}

/**
 * Opacity of an enclosing scope relative to the innermost one. The active guide keeps
 * full opacity; each parent level fades one step and never drops below 25 %, so outer
 * guides remain visible without competing with the current scope.
 */
internal fun scopeGuideAlpha(depth: Int): Float =
    (1f - depth.coerceAtLeast(0) * ENCLOSING_SCOPE_ALPHA_STEP).coerceAtLeast(MIN_ENCLOSING_SCOPE_ALPHA)

private const val ENCLOSING_SCOPE_ALPHA_STEP = 0.25f
private const val MIN_ENCLOSING_SCOPE_ALPHA = 0.25f

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

/**
 * Computes the vertical extent of the guide. Guides that join line centers reach
 * the middle of the opener and closing lines so the rounded corners can touch the
 * text. Column-0 guides have no arms, so they run from the bottom of the opener
 * line to the top of the closing line and never overlap either line. Returns null
 * when there is no vertical space to paint, such as a scope that opens and closes
 * on adjacent lines.
 */
internal fun guideVerticalExtent(
    firstLineTopY: Int,
    firstLineBottomY: Int,
    lastLineTopY: Int,
    lastLineBottomY: Int,
    joinLineCenters: Boolean,
    endsAtClosingLine: Boolean = true,
): IntRange? {
    val top = if (joinLineCenters) firstLineTopY + (firstLineBottomY - firstLineTopY) / 2 else firstLineBottomY
    val bottom = if (joinLineCenters) {
        lastLineTopY + (lastLineBottomY - lastLineTopY) / 2
    } else if (endsAtClosingLine) {
        lastLineTopY
    } else {
        lastLineBottomY
    }
    return if (bottom > top) top..bottom else null
}
