package com.selxo.rougo.windows

import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities

internal class SubtitleVideoOverlay(
    onSubtitleClick: (String, Int) -> Unit
) : JWindow() {
    private val subtitleComponent = SubtitleOverlayComponent(onSubtitleClick)

    init {
        background = java.awt.Color(0, 0, 0, 0)
        setFocusableWindowState(false)
        setAutoRequestFocus(false)
        contentPane = subtitleComponent
    }

    fun updateSubtitle(text: String, visible: Boolean, lookupEnabled: Boolean) {
        val displayText = subtitleOverlayLookupText(text)
        val shouldShow = visible && displayText.isNotBlank()
        val update = {
            subtitleComponent.subtitleText = if (shouldShow) displayText else ""
            subtitleComponent.lookupEnabled = lookupEnabled
            subtitleComponent.repaint()
            if (isVisible != shouldShow) {
                isVisible = shouldShow
            }
        }
        if (SwingUtilities.isEventDispatchThread()) update() else SwingUtilities.invokeLater(update)
    }

    fun clearSubtitle() {
        updateSubtitle("", visible = false, lookupEnabled = false)
    }
}

private class SubtitleOverlayComponent(
    private val onSubtitleClick: (String, Int) -> Unit
) : JComponent() {
    var subtitleText: String = ""
    var lookupEnabled: Boolean = true
    private val subtitleFont = Font("Yu Gothic UI", Font.BOLD, 28)

    init {
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!lookupEnabled || event.button != MouseEvent.BUTTON1 || event.clickCount != 1) return
                val offset = subtitleOffsetAt(event.x, event.y) ?: return
                onSubtitleClick(subtitleText, offset)
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                cursor = if (lookupEnabled && subtitleOffsetAt(event.x, event.y) != null) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }
        })
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val text = subtitleText
        if (text.isBlank() || width <= 0 || height <= 0) return

        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.font = subtitleFont
            val lines = subtitleOverlayLayout(text, width, height, g2.fontMetrics)
            val outlineStroke = BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            lines.forEach { line ->
                val glyphs = subtitleFont.createGlyphVector(g2.fontRenderContext, line.text)
                val shape = glyphs.getOutline(line.x.toFloat(), line.baseline.toFloat())
                g2.stroke = outlineStroke
                g2.color = java.awt.Color(0, 0, 0, 210)
                g2.draw(shape)
                g2.color = java.awt.Color.WHITE
                g2.fill(shape)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun subtitleOffsetAt(x: Int, y: Int): Int? {
        val text = subtitleText
        if (text.isBlank() || width <= 0 || height <= 0) return null
        val metrics = getFontMetrics(subtitleFont) ?: return null
        val lines = subtitleOverlayLayout(text, width, height, metrics)
        return subtitleOffsetAt(x, y, text, lines, metrics)
    }
}

private data class SubtitleOverlayLine(
    val text: String,
    val startIndex: Int,
    val endIndex: Int,
    val x: Int,
    val top: Int,
    val baseline: Int,
    val width: Int,
    val height: Int
)

internal fun subtitleOverlayLookupText(text: String): String =
    text
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

private fun subtitleOverlayLayout(
    text: String,
    surfaceWidth: Int,
    surfaceHeight: Int,
    metrics: FontMetrics
): List<SubtitleOverlayLine> {
    val maxLineWidth = (surfaceWidth * 0.86f).toInt().coerceAtLeast(160)
    val wrapped = wrapSubtitleOverlayText(text, metrics, maxLineWidth)
    if (wrapped.isEmpty()) return emptyList()

    val lineGap = 2
    val lineHeight = metrics.height.coerceAtLeast(1)
    val totalTextHeight = wrapped.size * lineHeight + (wrapped.size - 1) * lineGap
    val bottomMargin = (surfaceHeight * 0.045f).toInt().coerceIn(14, 40)
    val top = surfaceHeight - bottomMargin - totalTextHeight

    return wrapped.mapIndexed { index, line ->
        val lineTop = top + index * (lineHeight + lineGap)
        SubtitleOverlayLine(
            text = text.substring(line.startIndex, line.endIndex),
            startIndex = line.startIndex,
            endIndex = line.endIndex,
            x = (surfaceWidth - line.width) / 2,
            top = lineTop,
            baseline = lineTop + metrics.ascent,
            width = line.width,
            height = lineHeight
        )
    }
}

private data class WrappedSubtitleLine(
    val startIndex: Int,
    val endIndex: Int,
    val width: Int
)

private fun wrapSubtitleOverlayText(
    text: String,
    metrics: FontMetrics,
    maxWidth: Int
): List<WrappedSubtitleLine> {
    val lines = mutableListOf<WrappedSubtitleLine>()
    var lineStart = 0
    var cursor = 0
    var lastSpace = -1
    var currentWidth = 0

    fun addLine(start: Int, end: Int) {
        var safeStart = start.coerceIn(0, text.length)
        var safeEnd = end.coerceIn(safeStart, text.length)
        while (safeStart < safeEnd && text[safeStart].isWhitespace()) safeStart++
        while (safeEnd > safeStart && text[safeEnd - 1].isWhitespace()) safeEnd--
        if (safeEnd > safeStart) {
            lines += WrappedSubtitleLine(
                startIndex = safeStart,
                endIndex = safeEnd,
                width = metrics.stringWidth(text.substring(safeStart, safeEnd)).coerceAtLeast(1)
            )
        }
    }

    while (cursor < text.length) {
        val char = text[cursor]
        val charWidth = metrics.charWidth(char).coerceAtLeast(1)
        if (char.isWhitespace()) lastSpace = cursor

        if (currentWidth + charWidth > maxWidth && cursor > lineStart) {
            val breakIndex = if (lastSpace > lineStart) lastSpace else cursor
            addLine(lineStart, breakIndex)
            lineStart = if (lastSpace > lineStart) lastSpace + 1 else cursor
            while (lineStart < text.length && text[lineStart].isWhitespace()) lineStart++
            cursor = lineStart
            lastSpace = -1
            currentWidth = 0
        } else {
            currentWidth += charWidth
            cursor++
        }
    }
    addLine(lineStart, text.length)
    return lines
}

private fun subtitleOffsetAt(
    x: Int,
    y: Int,
    text: String,
    lines: List<SubtitleOverlayLine>,
    metrics: FontMetrics
): Int? {
    val verticalPadding = 10
    val horizontalPadding = 12
    lines.forEach { line ->
        if (y < line.top - verticalPadding || y > line.top + line.height + verticalPadding) return@forEach
        if (x < line.x - horizontalPadding || x > line.x + line.width + horizontalPadding) return null
        val localX = (x - line.x).coerceIn(0, line.width)
        var cursorX = 0
        for (index in line.startIndex until line.endIndex) {
            val charWidth = metrics.charWidth(text[index]).coerceAtLeast(1)
            if (localX <= cursorX + charWidth / 2) return index
            cursorX += charWidth
        }
        return (line.endIndex - 1).coerceAtLeast(line.startIndex)
    }
    return null
}
