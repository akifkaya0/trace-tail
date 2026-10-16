package io.github.akifkaya0.tracetail.ui

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.Level
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.Phase
import io.github.akifkaya0.tracetail.model.TraceModel
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseEvent
import java.util.IdentityHashMap
import javax.swing.JComponent

/**
 * The Flat tab: every line in arrival order, coloured as in the tree; warnings and errors have a
 * tinted background. Each line names its request by the first characters of its id, a link in the
 * request's colour. Clicking it focuses the request: the other lines fade until it is clicked again.
 */
internal class FlatConsole(project: Project, parent: Disposable, private val model: TraceModel) {

    // by identity: the attributes are constants, and their colours are slow to hash
    private val types = IdentityHashMap<SimpleTextAttributes, ConsoleViewContentType>()

    /** The focused request, by the part of its id that the lines show. */
    private var focus: String? = null

    private val view = LogView(project, parent, ::parts, ::styleOf)

    val component: JComponent get() = view.component

    val editor: Editor get() = view.editor

    /** The lines shown. */
    val lineCount: Int get() = view.lineCount

    init {
        view.editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                if (e.mouseEvent.button != MouseEvent.BUTTON1) return
                idAt(e)?.let { setFocus(if (focus == it) null else it) }
            }
        }, parent)
        view.editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                view.editor.setCustomCursor(this@FlatConsole, idAt(e)?.let { Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) })
            }
        }, parent)
    }

    fun setSoftWraps(on: Boolean) = view.setSoftWraps(on)

    fun print(change: Change) {
        // the focused request may be gone: cleared, or below the level now
        if (change.reset && focus != null && change.admitted.none { it.trace?.take(ID_LENGTH) == focus }) focus = null
        view.show(change)
    }

    private fun parts(l: LogLine): List<Pair<String, ConsoleViewContentType>> =
        header(l, model.appIndex(l.app)).map { (text, attributes) -> text to type(attributes) } +
            listOfNotNull(stackTrace(l)?.let { it to ConsoleViewContentType.ERROR_OUTPUT })

    private fun type(attributes: SimpleTextAttributes): ConsoleViewContentType =
        if (attributes === Palette.PLAIN) {
            ConsoleViewContentType.NORMAL_OUTPUT
        } else {
            types.getOrPut(attributes) { ConsoleViewContentType("TraceTail${types.size}", attributes.toTextAttributes()) }
        }

    /** The request id under the mouse. */
    private fun idAt(e: EditorMouseEvent): String? {
        if (!e.isOverText) return null
        val (line, start) = view.lineAt(e.offset) ?: return null
        val id = line.trace?.take(ID_LENGTH) ?: return null
        return id.takeIf { e.offset - start in ID_FROM until ID_FROM + id.length }
    }

    /* ---------- tints and focus ---------- */

    /** Focuses the line's request and puts the caret on the line. */
    fun show(line: LogLine) {
        setFocus(line.trace?.take(ID_LENGTH) ?: return)
        view.scrollTo(line)
    }

    private fun setFocus(id: String?) {
        focus = id
        view.restyle()
    }

    /** Fades the lines of every request but the focused one, and tints warnings and errors; a faded line is not tinted. */
    private fun styleOf(l: LogLine): TextAttributes? = when {
        focus != null && l.trace?.take(ID_LENGTH) != focus -> FADED
        l.level == Level.ERROR -> ERROR_LINES
        l.level == Level.WARN -> WARN_LINES
        else -> null
    }

    companion object {
        /** A line starts with its time and level, then its request's id: "12:00:00.000 INFO  1a2b3c4d ". */
        private const val ID_FROM = 19
        private const val ID_LENGTH = 8
        private val HIDDEN_FIELDS = setOf("startTime")
        private val FADED = TextAttributes(Palette.fadedColor, null, null, null, Font.PLAIN)
        private val WARN_LINES = TextAttributes(null, Palette.warnBackground, null, null, Font.PLAIN)
        private val ERROR_LINES = TextAttributes(null, Palette.errorBackground, null, null, Font.PLAIN)

        /** The line as the Flat tab writes it, without its stack trace, in parts with their colours. */
        private fun header(l: LogLine, appIndex: Int): List<Part> {
            val parts = ArrayList<Part>()
            parts += Palette.time(l.time) + " " to Palette.GRAY
            parts += l.level.name.padEnd(5) to Palette.level(l.level)
            parts += " " to Palette.PLAIN
            val id = l.trace.orEmpty().take(ID_LENGTH)
            parts += id to Palette.trace(id)
            parts += " ".repeat(ID_LENGTH - id.length + 1) to Palette.PLAIN
            parts += l.app to Palette.app(appIndex)
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
            return parts
        }

        private fun stackTrace(l: LogLine): String? = l.stackTrace?.let { it.replace("\r\n", "\n") + "\n" }

        /** The text the line takes in the Flat tab, which the limit of its run counts. */
        fun length(l: LogLine): Int = header(l, 0).sumOf { it.first.length } + (stackTrace(l)?.length ?: 0)
    }
}
