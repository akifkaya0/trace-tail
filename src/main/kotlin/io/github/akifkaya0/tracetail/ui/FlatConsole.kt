package io.github.akifkaya0.tracetail.ui

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SimpleTextAttributes
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.Level
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.Phase
import io.github.akifkaya0.tracetail.model.TraceModel
import java.awt.Font
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * The Flat tab: every line in arrival order, in a console, coloured as in the tree; warnings and
 * errors have a tinted background. Each line names its request by the first characters of its id,
 * a link in the request's colour. Clicking it focuses the request: the other lines fade until it is
 * clicked again.
 */
internal class FlatConsole(project: Project, parent: Disposable, private val model: TraceModel) {

    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
    private val types = HashMap<SimpleTextAttributes, ConsoleViewContentType>()
    private val styled = ArrayList<RangeHighlighter>()
    private var restylePending = false

    /** The focused request, by the part of its id that the lines show. */
    private var focus: String? = null

    val component: JComponent = console.component

    val editor: Editor? get() = (console as? ConsoleViewImpl)?.editor

    init {
        Disposer.register(parent, console)
        console.addMessageFilter(RequestLinks())
        // the console adds printed text a little later; the new lines are tinted or faded once they are there
        editor?.document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleRestyle()
        }, console)
    }

    fun setSoftWraps(on: Boolean) = console.setSoftWraps(on)

    fun print(change: Change) {
        if (change.reset) {
            console.clear()
            // the focused request may be gone: cleared, or below the level now
            if (focus != null && change.admitted.none { it.trace?.take(ID_LENGTH) == focus }) setFocus(null)
        }
        for (line in change.admitted) print(line)
    }

    private fun print(l: LogLine) {
        val parts = ArrayList<Part>()
        parts += Palette.time(l.time) + " " to Palette.GRAY
        parts += l.level.name.padEnd(5) to Palette.level(l.level)
        parts += " " + l.trace.orEmpty().take(ID_LENGTH).padEnd(ID_LENGTH) + " " to Palette.PLAIN
        parts += l.app to Palette.app(model.appIndex(l.app))
        parts += "  " to Palette.PLAIN
        parts += l.title to if (l.event != null) Palette.BOLD else Palette.PLAIN
        l.phase?.let { parts += " $it" to if (it == Phase.START) Palette.START else Palette.END }
        l.user?.let {
            parts += " user=" to Palette.GRAY
            parts += it to Palette.PLAIN
        }
        for ((key, value) in l.fields) {
            if (key in HIDDEN_FIELDS) continue
            parts += " $key=" to Palette.GRAY
            parts += value to Palette.value(key, value)
        }
        parts += "\n" to Palette.PLAIN
        for ((text, attributes) in parts) console.print(text, type(attributes))
        l.stackTrace?.let { console.print(it.replace("\r\n", "\n") + "\n", ConsoleViewContentType.ERROR_OUTPUT) }
    }

    private fun type(attributes: SimpleTextAttributes): ConsoleViewContentType =
        if (attributes == Palette.PLAIN) {
            ConsoleViewContentType.NORMAL_OUTPUT
        } else {
            types.getOrPut(attributes) { ConsoleViewContentType("TraceTail${types.size}", attributes.toTextAttributes()) }
        }

    /* ---------- tints and focus ---------- */

    /** Focuses the line's request and puts the caret on the line. */
    fun show(line: LogLine) {
        val id = line.trace?.take(ID_LENGTH) ?: return
        setFocus(id)
        val editor = editor ?: return
        // the start of the line as [print] writes it; its time to the millisecond makes it unique enough
        val start = Palette.time(line.time) + " " + line.level.name.padEnd(5) + " " + id.padEnd(ID_LENGTH) + " " + line.app + "  " + line.title
        val offset = editor.document.immutableCharSequence.indexOf(start)
        if (offset < 0) return
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun setFocus(id: String?) {
        focus = id
        restyle()
    }

    private fun scheduleRestyle() {
        if (restylePending) return
        restylePending = true
        SwingUtilities.invokeLater {
            restylePending = false
            restyle()
        }
    }

    /**
     * Tints the entries of warnings and errors, and fades the entries of every request but the
     * focused one; a faded entry is not tinted. An entry is a line with the lines below it that start
     * no entry of their own, such as its stack trace.
     */
    private fun restyle() {
        styled.forEach { it.dispose() }
        styled.clear()
        val editor = editor ?: return
        val document = editor.document
        val text = document.immutableCharSequence
        var from = 0
        var style: TextAttributes? = if (focus != null) FADED else null
        for (i in 0 until document.lineCount) {
            val start = document.getLineStartOffset(i)
            val entry = entryAt(text, start, document.getLineEndOffset(i)) ?: continue
            val next = styleOf(entry)
            if (next == style) continue
            if (i > 0) mark(editor, from, document.getLineEndOffset(i - 1), style)
            from = start
            style = next
        }
        mark(editor, from, document.textLength, style)
    }

    private fun styleOf(entry: Entry): TextAttributes? = when {
        focus != null && entry.id != focus -> FADED
        entry.level == Level.ERROR -> ERROR_LINES
        entry.level == Level.WARN -> WARN_LINES
        else -> null
    }

    private fun mark(editor: Editor, from: Int, to: Int, style: TextAttributes?) {
        if (style == null || to <= from) return
        val markup = editor.markupModel
        // a tint fills its lines from edge to edge; a fade recolours the text, links included
        styled += if (style == FADED) {
            markup.addRangeHighlighter(from, to, HighlighterLayer.HYPERLINK + 1, style, HighlighterTargetArea.EXACT_RANGE)
        } else {
            markup.addRangeHighlighter(from, to, HighlighterLayer.CARET_ROW - 1, style, HighlighterTargetArea.LINES_IN_RANGE)
        }
    }

    /** Makes each line's request id a link in the request's colour, which focuses the request. */
    private inner class RequestLinks : Filter, DumbAware {
        override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
            val id = entryAt(line, 0, line.length)?.id?.takeIf { it.isNotEmpty() } ?: return null
            val start = entireLength - line.length + ID_FROM
            val color = Palette.traceColor(id)
            val shown = TextAttributes(color, null, null, null, Font.PLAIN)
            val link = HyperlinkInfo { setFocus(if (focus == id) null else id) }
            return Filter.Result(listOf(Filter.ResultItem(start, start + id.length, link, shown, shown)))
        }
    }

    /** The start of an entry: its request's id, "" outside any request, and its level. */
    private class Entry(val id: String, val level: Level)

    private companion object {
        /** A line starts with its time and level, then its request's id: "12:00:00.000 INFO  1a2b3c4d ". */
        const val ID_FROM = 19
        const val ID_LENGTH = 8
        val HIDDEN_FIELDS = setOf("startTime")
        val FADED = TextAttributes(Palette.fadedColor, null, null, null, Font.PLAIN)
        val WARN_LINES = TextAttributes(null, Palette.warnBackground, null, null, Font.PLAIN)
        val ERROR_LINES = TextAttributes(null, Palette.errorBackground, null, null, Font.PLAIN)

        /** The entry that starts on a line, or null for a line that continues the entry above, such as a stack trace's. */
        fun entryAt(text: CharSequence, start: Int, end: Int): Entry? {
            if (end - start < ID_FROM + ID_LENGTH) return null
            for (i in 0 until 12) {
                val c = text[start + i]
                val ok = when (i) {
                    2, 5 -> c == ':'
                    8 -> c == '.'
                    else -> c.isDigit()
                }
                if (!ok) return null
            }
            if (text[start + 12] != ' ' || text[start + ID_FROM - 1] != ' ') return null
            val name = text.subSequence(start + 13, start + 18).trim().toString()
            val level = Level.entries.firstOrNull { it.name == name } ?: return null
            return Entry(text.subSequence(start + ID_FROM, start + ID_FROM + ID_LENGTH).trim().toString(), level)
        }
    }
}
