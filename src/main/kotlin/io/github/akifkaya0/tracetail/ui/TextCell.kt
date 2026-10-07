package io.github.akifkaya0.tracetail.ui

import com.intellij.ide.ui.UISettings
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

internal typealias Part = Pair<String, SimpleTextAttributes>

/**
 * A table cell of coloured text, optionally indented behind a fold arrow, on one line or wrapped
 * at the cell's width. Rows of different heights keep their first line at the top.
 */
internal class TextCell(
    private val table: JTable,
    private val configure: TextCell.(value: Any?) -> Unit,
) : JComponent(), TableCellRenderer {

    var parts: List<Part> = emptyList()

    /** Pixels before the arrow slot; nonzero only in the tree column. */
    var indent = 0

    /** The fold arrow, or null for a row that does not fold. */
    var arrow: Icon? = null

    /** Whether the cell keeps room for an arrow, so rows that do not fold line up with those that do. */
    var arrowSlot = false

    var wrap = false

    private var foreground0: Color? = null

    val lineHeight: Int get() = table.getFontMetrics(table.font).height + JBUI.scale(2)
    val rowHeight: Int get() = lineHeight + 2 * vpad()
    val textStart: Int get() = pad() + indent + if (arrowSlot) arrowWidth() else 0

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        background = RenderingUtil.getBackground(table, selected)
        foreground0 = RenderingUtil.getForeground(table, selected)
        font = table.font
        configure(value)
        return this
    }

    /** The height the cell needs at [width], wrapping when [wrap] is on. */
    fun heightFor(value: Any?, width: Int): Int {
        font = table.font
        configure(value)
        return 2 * vpad() + layout(null, width) * lineHeight
    }

    /** The width the cell needs to show its text on one line. */
    fun widthFor(value: Any?): Int {
        font = table.font
        configure(value)
        val fm = table.getFontMetrics(table.font)
        return textStart + parts.sumOf { (text, attributes) -> table.getFontMetrics(fontFor(attributes)).stringWidth(text) } +
            pad() + (fm.charWidth('m'))
    }

    /** The width a column needs to show each of [texts] on one line, without indent. */
    fun widthOf(texts: List<Part>): Int =
        2 * pad() + JBUI.scale(4) + (texts.maxOfOrNull { (text, attributes) -> table.getFontMetrics(fontFor(attributes)).stringWidth(text) } ?: 0)

    override fun paintComponent(g0: Graphics) {
        g0.color = background
        g0.fillRect(0, 0, width, height)
        val g = g0.create() as Graphics2D
        try {
            UISettings.setupAntialiasing(g)
            arrow?.let { it.paintIcon(this, g, pad() + indent, vpad() + (lineHeight - it.iconHeight) / 2) }
            layout(g, width)
        } finally {
            g.dispose()
        }
    }

    /** Lays the parts out from [textStart] to [width]; draws them when [g] is given. Returns the number of lines. */
    private fun layout(g: Graphics2D?, width: Int): Int {
        val start = textStart
        val right = maxOf(width - pad(), start + JBUI.scale(40))
        var x = start
        var line = 0
        for ((text, attributes) in parts) {
            val f = fontFor(attributes)
            val fm = table.getFontMetrics(f)
            g?.font = f
            g?.color = attributes.fgColor ?: foreground0 ?: table.foreground
            for (token in text.split(WORD_END)) {
                if (token.isEmpty()) continue
                var rest = token
                while (rest.isNotEmpty()) {
                    val w = fm.stringWidth(rest)
                    if (!wrap || x + w <= right) {
                        g?.drawString(rest, x, baseline(line, fm))
                        x += w
                        break
                    }
                    if (x > start) {
                        // the word moves to the next line; a space at a line's start is dropped
                        line++
                        x = start
                        if (rest.isBlank()) break
                        continue
                    }
                    // a word longer than the whole line is cut where the line ends
                    var n = rest.length - 1
                    while (n > 1 && fm.stringWidth(rest.substring(0, n)) > right - x) n--
                    g?.drawString(rest.substring(0, n), x, baseline(line, fm))
                    rest = rest.substring(n)
                    line++
                    x = start
                }
            }
        }
        return line + 1
    }

    private fun baseline(line: Int, fm: java.awt.FontMetrics) = vpad() + line * lineHeight + (lineHeight - fm.height) / 2 + fm.ascent

    private fun fontFor(attributes: SimpleTextAttributes): Font {
        val style = (if (attributes.style and SimpleTextAttributes.STYLE_BOLD != 0) Font.BOLD else 0) or (if (attributes.style and SimpleTextAttributes.STYLE_ITALIC != 0) Font.ITALIC else 0)
        return if (style == 0) table.font else table.font.deriveFont(style)
    }

    companion object {
        private fun pad() = JBUI.scale(6)
        private fun vpad() = JBUI.scale(2)
        private fun arrowWidth() = JBUI.scale(18)

        /** Splits after each run of spaces, keeping the spaces with the word before them. */
        private val WORD_END = Regex("(?<=\\s)(?=\\S)")

        /** The width of [depth] indent levels. */
        fun indent(depth: Int) = depth * JBUI.scale(16)

        /** Whether [x], within a cell, is on the fold arrow of a row at [depth]. */
        fun onArrow(x: Int, depth: Int): Boolean {
            val from = pad() + indent(depth)
            return x in from until from + arrowWidth()
        }
    }
}
