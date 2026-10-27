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
    internal val customColor: Color?,
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
