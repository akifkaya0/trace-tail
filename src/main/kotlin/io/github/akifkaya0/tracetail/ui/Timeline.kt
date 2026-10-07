package io.github.akifkaya0.tracetail.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.JBUI
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.Step
import io.github.akifkaya0.tracetail.model.Trace
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/** Where a row sits on its request's own time line, as fractions of the request's duration. */
internal class Bar(val from: Float, val to: Float, val kind: Kind, val color: Color) {
    enum class Kind { DONE, RUNNING, LOST, MARK }
}

/**
 * A request's time line: from its first line to its last, or to now while a step runs. Needs
 * [Trace.analyse] to have run, for the steps that will not finish.
 */
internal class TraceWindow(private val trace: Trace, private val now: Long) {
    private val from: Long
    private val span: Double

    init {
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        for (line in trace.lines) {
            lo = minOf(lo, line.time)
            hi = maxOf(hi, line.time)
        }
        for (s in trace.steps.values) {
            lo = minOf(lo, s.start)
            hi = maxOf(hi, end(s))
        }
        from = lo
        span = maxOf(hi - lo, 1).toDouble()
    }

    /** A step nobody closed stops where its nearest finished caller stopped. */
    fun end(s: Step): Long {
        s.end?.let { return it }
        if (!s.unfinished) return now
        var parent = trace.steps[s.parent]
        var hops = 0
        while (parent != null && hops++ < trace.steps.size) {
            parent.end?.let { return it }
            parent = trace.steps[parent.parent]
        }
        return trace.lines.last().time
    }

    fun step(s: Step, color: Color): Bar {
        val kind = when {
            s.end != null -> Bar.Kind.DONE
            s.unfinished -> Bar.Kind.LOST
            else -> Bar.Kind.RUNNING
        }
        return Bar(at(s.start), at(end(s)), kind, color)
    }

    fun mark(line: LogLine) = Bar(at(line.time), at(line.time), Bar.Kind.MARK, Palette.levelColor(line.level))

    fun whole(running: Boolean, color: Color) = Bar(0f, 1f, if (running) Bar.Kind.RUNNING else Bar.Kind.DONE, color)

    private fun at(time: Long) = ((time - from) / span).toFloat().coerceIn(0f, 1f)
}

/** Draws a row's [Bar]: a step as a bar in its app's colour, a line inside a step as a diamond. */
internal class TimelineRenderer : JComponent(), TableCellRenderer {
    private var bar: Bar? = null

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        bar = (value as? LineRow)?.bar
        background = RenderingUtil.getBackground(table, selected)
        return this
    }

    override fun paintComponent(g: Graphics) {
        g.color = background
        g.fillRect(0, 0, width, height)
        val b = bar ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val pad = JBUI.scale(6)
            val w = (width - 2 * pad).coerceAtLeast(1)
            val h = JBUI.scale(8)
            val y = (height - h) / 2
            val x0 = pad + (b.from * w).toInt()
            if (b.kind == Bar.Kind.MARK) {
                val r = h / 2
                g2.color = b.color
                g2.fill(Polygon(intArrayOf(x0, x0 + r, x0, x0 - r), intArrayOf(y, y + r, y + h, y + r), 4))
                return
            }
            val x1 = maxOf(x0 + JBUI.scale(2), pad + (b.to * w).toInt())
            val arc = JBUI.scale(3).toFloat()
            val shape = RoundRectangle2D.Float(x0.toFloat(), y.toFloat(), (x1 - x0).toFloat(), h.toFloat(), arc, arc)
            when (b.kind) {
                Bar.Kind.DONE -> {
                    g2.color = b.color
                    g2.fill(shape)
                }
                Bar.Kind.RUNNING -> {
                    g2.color = ColorUtil.withAlpha(b.color, 0.3)
                    g2.fill(shape)
                    g2.color = Palette.accentColor
                    g2.draw(shape)
                }
                Bar.Kind.LOST -> {
                    g2.color = ColorUtil.withAlpha(Palette.errorColor, 0.2)
                    g2.fill(shape)
                    g2.color = Palette.errorColor
                    g2.draw(shape)
                }
                Bar.Kind.MARK -> Unit
            }
        } finally {
            g2.dispose()
        }
    }
}
