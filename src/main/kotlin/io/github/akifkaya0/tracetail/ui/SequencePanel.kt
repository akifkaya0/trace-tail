package io.github.akifkaya0.tracetail.ui

import com.intellij.ide.ui.UISettings
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.akifkaya0.tracetail.model.Level
import io.github.akifkaya0.tracetail.model.Trace
import io.github.akifkaya0.tracetail.model.TraceModel
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.ToolTipManager

/** How an arrow is drawn: a call with a filled head, a message with an open one, a return dashed. */
private enum class Kind { CALL, ASYNC, RETURN }

/** Draws the selected request's [Sequence]; a row's tooltip is its line. */
internal class SequencePanel(private val model: TraceModel) : JPanel() {

    private var layout: Layout? = null

    /** The request shown, if any. */
    val trace: Trace? get() = layout?.seq?.trace

    /** Whether a step is still running, so the diagram needs redrawing as time passes. */
    val running: Boolean get() = layout?.seq?.activations?.any { it.running } == true

    val mermaid: String? get() = layout?.seq?.mermaid()

    init {
        isOpaque = true
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    fun show(trace: Trace?) {
        val now = System.currentTimeMillis()
        layout = trace?.let {
            it.analyse(now)
            Layout(Sequence(it) { app -> app in model.apps }, now)
        }
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension = layout?.let { Dimension(it.width, it.height) }
        ?: Dimension(getFontMetrics(JBUI.Fonts.smallFont()).stringWidth(HINT) + JBUI.scale(16), JBUI.scale(28))

    override fun getToolTipText(event: MouseEvent): String? {
        val l = layout ?: return null
        val i = Math.floorDiv(event.y - l.top + l.rowH / 2, l.rowH)
        val row = l.seq.rows.getOrNull(i) ?: return null
        val line = row.line
        val fields = line.fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        return listOfNotNull(Palette.time(line.time), line.level.name, line.app, line.title, line.phase?.name, fields).joinToString(" ")
    }

    override fun paintComponent(g0: Graphics) {
        super.paintComponent(g0)
        val l = layout
        val g = g0.create() as Graphics2D
        try {
            UISettings.setupAntialiasing(g)
            g.font = l?.font ?: JBUI.Fonts.smallFont()
            if (l == null) {
                g.color = UIUtil.getContextHelpForeground()
                g.drawString(HINT, JBUI.scale(8), JBUI.scale(20))
                return
            }
            l.paint(g)
        } finally {
            g.dispose()
        }
    }

    /** Positions computed once per request, used for both the size and the drawing. */
    private inner class Layout(val seq: Sequence, private val now: Long) {
        val font: Font = JBUI.Fonts.smallFont()
        private val fm = getFontMetrics(font)
        val rowH = fm.height + JBUI.scale(8)
        val top = JBUI.scale(18) + rowH
        private val bar = JBUI.scale(8)
        private val gutter = fm.stringWidth("+00.0 s") + JBUI.scale(10)
        private val index = HashMap<String, Int>()
        private val colW: IntArray
        private val cx: IntArray

        /** Where the bars of the steps without an end stop; their texts go one per line below. */
        private val openEnd: Int
        private val open: List<Sequence.Activation> = seq.activations.filter { it.r1 == null && (it.running || it.lost) }
        private val endY: Int
        val width: Int
        val height: Int

        private val ink: Color get() = UIUtil.getLabelForeground()
        private val muted: Color get() = UIUtil.getContextHelpForeground()
        private val lifeline: Color get() = JBColor.GRAY

        init {
            seq.parts.forEachIndexed { i, p -> index[p.key] = i }
            val n = seq.parts.size
            colW = IntArray(n) { maxOf(JBUI.scale(64), tw(seq.parts[it].label) + JBUI.scale(14)) }
            // neighbouring lifelines move apart only as far as the labels between them need
            val gap = IntArray(n) { if (it < n - 1) colW[it] / 2 + colW[it + 1] / 2 + JBUI.scale(8) else 0 }
            for (r in seq.rows) {
                val (from, to, text) = when (r) {
                    is Sequence.Call -> Triple(r.from, r.to, seq.callLabel(r))
                    is Sequence.Return -> Triple(r.from, r.to, seq.returnLabel(r).first)
                    is Sequence.Note -> continue
                }
                val a = index[from] ?: continue
                val b = index[to] ?: continue
                if (a == b) continue
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                val need = tw(text) + JBUI.scale(22)
                val have = (lo until hi).sumOf { gap[it] }
                if (have < need) {
                    val add = (need - have + (hi - lo) - 1) / (hi - lo)
                    for (i in lo until hi) gap[i] += add
                }
            }
            cx = IntArray(n)
            var x = gutter + (colW.firstOrNull() ?: 0) / 2
            for (i in 0 until n) {
                cx[i] = x
                x += gap[i]
            }
            val lastY = if (seq.rows.isEmpty()) top else y(seq.rows.size - 1)
            openEnd = lastY + rowH / 2
            endY = if (open.isEmpty()) lastY + JBUI.scale(10) else openEnd + open.size * fm.height + JBUI.scale(4)
            height = endY + JBUI.scale(4)
            width = right() + JBUI.scale(8)
        }

        fun y(i: Int) = top + i * rowH

        private fun tw(text: String) = fm.stringWidth(text)

        private fun actX(a: Sequence.Activation) = cx[index.getValue(a.on)] - bar / 2 + a.depth * JBUI.scale(4)

        private fun activationEnd(a: Sequence.Activation) = a.r1?.let(::y) ?: openEnd

        /** The baseline of an open step's text: one line each, below the bars, so neighbours do not overlap. */
        private fun openTextY(a: Sequence.Activation) = openEnd + (open.indexOf(a) + 1) * fm.height - fm.descent

        /** Arrows attach to the edge of the bar they leave from and the bar they land on. */
        private fun edge(key: String, act: Sequence.Activation?, right: Boolean): Int =
            if (act != null && act.on == key) actX(act) + if (right) bar else 0 else cx[index.getValue(key)]

        private fun noteX(r: Sequence.Note): Int {
            val a = seq.activationOf[r.step]
            return if (a != null && a.on == r.on) actX(a) + bar + JBUI.scale(4) else cx[index.getValue(r.on)] + JBUI.scale(8)
        }

        private fun runningText(a: Sequence.Activation) = "running " + Palette.duration(now - a.step.start)

        private inner class Arrow(
            val points: List<Pair<Int, Int>>,
            val headRight: Boolean,
            val text: String,
            val tone: Color,
            val boxX: Int,
            val baseline: Int,
            val kind: Kind,
        ) {
            val boxW = tw(text) + JBUI.scale(6)
        }

        private fun arrow(i: Int, r: Sequence.Row): Arrow? {
            val (from, to) = when (r) {
                is Sequence.Call -> r.from to r.to
                is Sequence.Return -> r.from to r.to
                is Sequence.Note -> return null
            }
            val a = index[from] ?: return null
            val b = index[to] ?: return null
            val parent = seq.parentOf(r.step)
            val call = r is Sequence.Call
            val src = if (call) parent?.let { seq.activationOf[it] } else seq.activationOf[r.step]
            val dst = if (call) seq.activationOf[r.step] else parent?.let { seq.activationOf[it] }
            val (text, tone) = when (r) {
                is Sequence.Call -> seq.callLabel(r) to ink
                is Sequence.Return -> seq.returnLabel(r).let { (t, o) -> t to (o?.let { Palette.outcome(it).fgColor } ?: muted) }
                is Sequence.Note -> return null
            }
            val kind = if (!call) Kind.RETURN else if (r.step.event == "MQ_IN") Kind.ASYNC else Kind.CALL
            val y = y(i)
            val textMid = (fm.ascent - fm.descent) / 2
            return if (a == b) {
                // a call to the same lifeline loops out to the right and back into the nested bar
                val x1 = edge(from, src, true)
                val x2 = edge(to, dst, true)
                val xr = maxOf(x1, x2) + JBUI.scale(14)
                val d = JBUI.scale(4)
                Arrow(listOf(x1 to y - d, xr to y - d, xr to y + d, x2 to y + d), false, text, tone, xr + JBUI.scale(2), y + textMid, kind)
            } else {
                val right = b > a
                val x1 = edge(from, src, right)
                val x2 = edge(to, dst, !right)
                Arrow(listOf(x1 to y, x2 to y), right, text, tone, (x1 + x2) / 2 - (tw(text) + JBUI.scale(6)) / 2, y - JBUI.scale(3), kind)
            }
        }

        private fun right(): Int {
            val n = seq.parts.size
            var right = if (n > 0) cx[n - 1] + colW[n - 1] / 2 else 0
            for (a in seq.activations) {
                val text = if (a.lost) "unfinished" else if (a.running) runningText(a) else continue
                right = maxOf(right, actX(a) + bar + JBUI.scale(6) + tw(text))
            }
            seq.rows.forEachIndexed { i, r ->
                right = maxOf(
                    right,
                    when (r) {
                        is Sequence.Note -> noteX(r) + tw(seq.noteText(r)) + JBUI.scale(10)
                        else -> arrow(i, r)?.let { it.boxX + it.boxW } ?: 0
                    },
                )
            }
            return right
        }

        fun paint(g: Graphics2D) {
            val dashed = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(3f, 3f), 0f)
            val solid = BasicStroke(1.1f)

            // an app and its classes share a tinted background
            for ((group, members) in seq.parts.withIndex().groupBy { it.value.group }) {
                if (members.size < 2 || members.first().value.ext) continue
                val a = members.first().index
                val b = members.last().index
                val gx = cx[a] - colW[a] / 2 + JBUI.scale(2)
                val gw = cx[b] + colW[b] / 2 - JBUI.scale(2) - gx
                g.color = ColorUtil.withAlpha(appColor(group), 0.08)
                g.fill(RoundRectangle2D.Float(gx.toFloat(), 0f, gw.toFloat(), (endY + JBUI.scale(2)).toFloat(), 10f, 10f))
            }

            // lifelines and their names
            val boxH = JBUI.scale(18)
            seq.parts.forEachIndexed { i, p ->
                g.color = lifeline
                g.stroke = dashed
                g.drawLine(cx[i], boxH + JBUI.scale(1), cx[i], endY)
                g.stroke = BasicStroke(1f)
                val w = minOf(colW[i] - JBUI.scale(6), tw(p.label) + JBUI.scale(12))
                val box = RoundRectangle2D.Float((cx[i] - w / 2).toFloat(), 1f, w.toFloat(), boxH.toFloat(), 8f, 8f)
                g.color = if (p.ext) ColorUtil.withAlpha(lifeline, 0.15) else background
                g.fill(box)
                g.color = if (p.ext) lifeline else appColor(p.group)
                g.draw(box)
                g.font = font.deriveFont(Font.BOLD)
                g.color = if (p.ext) ink else appColor(p.group)
                val fmb = g.fontMetrics
                g.drawString(p.label, cx[i] - fmb.stringWidth(p.label) / 2, 1 + boxH / 2 + (fmb.ascent - fmb.descent) / 2)
                g.font = font
            }

            // activation bars
            for (a in seq.activations) {
                val p = seq.parts[index.getValue(a.on)]
                val x = actX(a)
                val y0 = y(a.r0)
                val y1 = activationEnd(a)
                g.color = background
                g.fillRect(x, y0, bar, maxOf(y1 - y0, JBUI.scale(4)))
                g.color = when {
                    a.lost -> Palette.errorColor
                    a.running -> Palette.accentColor
                    p.ext -> muted
                    else -> appColor(p.group)
                }
                g.stroke = if (a.running) dashed else BasicStroke(1f)
                g.drawRect(x, y0, bar, maxOf(y1 - y0, JBUI.scale(4)))
                g.stroke = BasicStroke(1f)
                if (a.lost) {
                    val c = x + bar / 2
                    val d = JBUI.scale(4)
                    g.stroke = BasicStroke(1.6f)
                    g.drawLine(c - d, y1 - d, c + d, y1 + d)
                    g.drawLine(c + d, y1 - d, c - d, y1 + d)
                    g.stroke = BasicStroke(1f)
                }
                val text = if (a.lost) "unfinished" else if (a.running) runningText(a) else null
                if (text != null) {
                    val baseline = if (a.r1 == null) openTextY(a) else y1 + (fm.ascent - fm.descent) / 2
                    g.drawString(text, x + bar + JBUI.scale(6), baseline)
                }
            }

            // rows: the time since the request began, then the call, return or note
            val t0 = seq.trace.lines.first().time
            val textMid = (fm.ascent - fm.descent) / 2
            seq.rows.forEachIndexed { i, r ->
                val y = y(i)
                g.color = muted
                g.drawString("+" + Palette.duration(r.line.time - t0), JBUI.scale(2), y + textMid)
                if (r is Sequence.Note) {
                    val nx = noteX(r)
                    val text = seq.noteText(r)
                    val w = tw(text) + JBUI.scale(10)
                    val h = JBUI.scale(14)
                    val box = RoundRectangle2D.Float(nx.toFloat(), (y - h / 2).toFloat(), w.toFloat(), h.toFloat(), 4f, 4f)
                    val level = r.line.level
                    val tint = if (level >= Level.WARN) Palette.levelColor(level) else Palette.accentColor
                    g.color = ColorUtil.withAlpha(tint, 0.15)
                    g.fill(box)
                    if (level >= Level.WARN) {
                        g.color = tint
                        g.draw(box)
                    }
                    g.color = ink
                    g.drawString(text, nx + JBUI.scale(5), y + textMid)
                    return@forEachIndexed
                }
                val arrow = arrow(i, r) ?: return@forEachIndexed
                val path = Path2D.Float()
                arrow.points.forEachIndexed { k, (px, py) -> if (k == 0) path.moveTo(px.toFloat(), py.toFloat()) else path.lineTo(px.toFloat(), py.toFloat()) }
                g.color = if (arrow.kind == Kind.RETURN) muted else ink
                g.stroke = if (arrow.kind == Kind.RETURN) BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 3f), 0f) else solid
                g.draw(path)
                g.stroke = BasicStroke(1.3f)
                val (hx, hy) = arrow.points.last()
                head(g, hx, hy, arrow.headRight, filled = arrow.kind == Kind.CALL)
                g.stroke = BasicStroke(1f)
                g.color = background
                g.fillRect(arrow.boxX, arrow.baseline - fm.ascent, arrow.boxW, fm.height)
                g.color = arrow.tone
                g.drawString(arrow.text, arrow.boxX + JBUI.scale(3), arrow.baseline)
            }
        }

        private fun head(g: Graphics2D, x: Int, y: Int, right: Boolean, filled: Boolean) {
            val len = JBUI.scale(6)
            val half = JBUI.scale(3)
            val back = if (right) x - len else x + len
            if (filled) {
                g.fill(Polygon(intArrayOf(x, back, back), intArrayOf(y, y - half, y + half), 3))
            } else {
                g.drawLine(back, y - half, x, y)
                g.drawLine(back, y + half, x, y)
            }
        }

        private fun appColor(app: String) = Palette.appColor(model.appIndex(app))
    }

    private companion object {
        const val HINT = "Select a line of a request to see its sequence diagram."
    }
}
