package io.github.akifkaya0.tracetail.ui

import com.intellij.ide.TreeExpander
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.Level
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.Step
import io.github.akifkaya0.tracetail.model.Trace
import io.github.akifkaya0.tracetail.model.TraceModel
import java.awt.BorderLayout
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.IdentityHashMap
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.ChangeEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import javax.swing.table.AbstractTableModel

/** A row of the tree. A row with a [step] is a START line whose children are the step's lines. */
internal class LineRow(
    val trace: Trace,
    val line: LogLine,
    val step: Step?,
    val group: Boolean,
    val summary: List<Part>,
)

/** Stands in for an END line that has not come, or never will. */
internal class PendingRow(val text: String, val lost: Boolean)

/** One row of a request's tree; [row] is a [LineRow] or a [PendingRow]. */
internal class Node(val traceId: String, val row: Any, val depth: Int) {
    val children = ArrayList<Node>()
}

/**
 * The requests as a tree: one row per request, opening into its steps, nested by span.id and
 * parent.id. The tree is drawn in a table, so that long lines can wrap: the table shows the open
 * rows, and the Message column draws each row's indent and fold arrow. Requests the reader opened,
 * and steps they folded, stay that way while lines arrive.
 */
internal class TraceTree(
    private val project: Project,
    private val model: TraceModel,
    /** Which requests this tree shows. */
    private val include: (Trace) -> Boolean,
    private val onSelect: (LogLine?) -> Unit,
) {

    /** The reader's choices for one request, kept across rebuilds. */
    private class TraceUi {
        var node: Node? = null
        var open = false
        val folded = HashSet<String?>()
        var wasRunning = false
    }

    private val ui = LinkedHashMap<String, TraceUi>()
    private val visible = ArrayList<Node>()
    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount() = visible.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getValueAt(row: Int, column: Int): Any = visible[row]
    }
    private var wrap = false
    private val table = object : JBTable(tableModel) {
        // Unwrapped lines keep their length and the table scrolls sideways; wrapped ones fit the width.
        override fun getScrollableTracksViewportWidth() = wrap || preferredSize.width < (parent?.width ?: 0)

        // A selected row comes into view up or down only; the wide Message cell would pull the view sideways.
        override fun changeSelection(row: Int, column: Int, toggle: Boolean, extend: Boolean) {
            val scrolls = autoscrolls
            autoscrolls = false
            super.changeSelection(row, column, toggle, extend)
            autoscrolls = scrolls
            if (scrolls) scrollToRow(row)
        }
    }
    private val scroll: JScrollPane = ScrollPaneFactory.createScrollPane(table)
    private val message = TextCell(table) { configureMessage(it as? Node) }
    private val heights = IdentityHashMap<Node, Int>()
    private val widths = IdentityHashMap<Node, Int>()
    private var heightsWidth = -1
    private var relayoutPending = false
    private var restoring = false
    private var selectedSeq: Long? = null

    val component: JComponent = object : JPanel(BorderLayout()), UiDataProvider {
        override fun uiDataSnapshot(sink: DataSink) {
            val line = selectedLine() ?: return
            sink.lazy(CommonDataKeys.NAVIGATABLE) { SourceNavigation.of(project, line) }
        }
    }.apply { add(scroll) }

    val expander = object : TreeExpander {
        override fun canExpand() = ui.isNotEmpty()
        override fun canCollapse() = ui.isNotEmpty()
        override fun expandAll() = setAllOpen(true)
        override fun collapseAll() = setAllOpen(false)
    }

    init {
        table.setShowGrid(false)
        table.intercellSpacing = JBUI.emptySize()
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.rowHeight = message.rowHeight
        table.tableHeader.reorderingAllowed = false
        table.tableHeader.resizingAllowed = false
        val columns = table.columnModel
        columns.getColumn(TIME).cellRenderer = TextCell(table) { v -> parts = lineOf(v)?.let { listOf(Palette.time(it.time) to Palette.GRAY) }.orEmpty() }
        columns.getColumn(LEVEL).cellRenderer = TextCell(table) { v -> parts = lineOf(v)?.let { listOf(it.level.name to Palette.level(it.level)) }.orEmpty() }
        columns.getColumn(APP).cellRenderer = TextCell(table) { v ->
            parts = lineOf(v)?.let { listOf(it.app to Palette.app(model.appIndex(it.app))) }.orEmpty()
        }
        columns.getColumn(MESSAGE).cellRenderer = message
        fitColumns()
        columns.addColumnModelListener(object : TableColumnModelListener {
            override fun columnMarginChanged(e: ChangeEvent) = scheduleRelayout()
            override fun columnAdded(e: TableColumnModelEvent) = Unit
            override fun columnRemoved(e: TableColumnModelEvent) = Unit
            override fun columnMoved(e: TableColumnModelEvent) = Unit
            override fun columnSelectionChanged(e: ListSelectionEvent) = Unit
        })
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !restoring) notifySelection()
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val column = table.columnAtPoint(e.point)
                val node = visible.getOrNull(row) ?: return
                if (node.children.isEmpty()) return
                val onArrow = column == MESSAGE && TextCell.onArrow(e.x - table.getCellRect(row, column, false).x, node.depth)
                if (onArrow || (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e))) toggle(node)
            }
        })
        bindKey(KeyEvent.VK_RIGHT, "traceTailExpand") { node -> if (node.children.isNotEmpty() && !isExpanded(node)) toggle(node) else selectRelative(1) }
        bindKey(KeyEvent.VK_LEFT, "traceTailCollapse") { node ->
            if (node.children.isNotEmpty() && isExpanded(node)) toggle(node) else selectParent(node)
        }
        // F4 and this menu open the class that wrote the line
        PopupHandler.installPopupMenu(
            table,
            DefaultActionGroup(ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE)),
            "TraceTailTree",
        )
    }

    fun selectedLine(): LogLine? = (visible.getOrNull(table.selectedRow)?.row as? LineRow)?.line

    /** Whether the newest rows are in view; new rows then keep them in view. */
    fun atBottom(): Boolean {
        val bar = scroll.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(8)
    }

    fun scrollToBottom() = scrollToRow(visible.lastIndex)

    fun setWrap(on: Boolean) {
        if (wrap == on) return
        wrap = on
        heights.clear()
        relayout()
        table.revalidate()
        table.repaint()
    }

    fun apply(change: Change) {
        val changed = change.changed.filter(include)
        if (!change.reset && changed.isEmpty() && change.removed.isEmpty()) return
        val follow = atBottom()
        if (change.reset) {
            val old = HashMap(ui)
            ui.clear()
            for (trace in changed) {
                val u = TraceUi()
                old[trace.id]?.let { u.open = it.open; u.folded += it.folded }
                ui[trace.id] = u
            }
        } else {
            for (trace in change.removed) ui.remove(trace.id)
            for (trace in changed) ui.getOrPut(trace.id) { TraceUi() }
        }
        changed.forEach(::rebuild)
        refresh()
        if (follow && changed.isNotEmpty()) scrollToBottom()
    }

    /** Redraws the requests with running steps, whose times keep growing. */
    fun refreshRunning() {
        val running = ui.filterValues { it.wasRunning }.keys.mapNotNull { model.traces[it] }
        if (running.isEmpty()) return
        running.forEach(::rebuild)
        refresh()
    }

    private fun rebuild(trace: Trace) {
        val u = ui[trace.id] ?: return
        val now = System.currentTimeMillis()
        val shape = trace.analyse(now)
        u.node = build(Build(trace, shape, now))
        u.wasRunning = shape.running > 0
    }

    /* ---------- building a request's rows ---------- */

    /** What building one request's rows needs. */
    private class Build(val trace: Trace, val shape: Trace.Shape, val now: Long)

    private fun build(b: Build): Node {
        val main = b.shape.roots.singleOrNull()?.takeIf { it.startLine != null }
        return if (main != null) {
            Node(b.trace.id, LineRow(b.trace, main.startLine!!, main, true, summary(b, main, true)), 0).also {
                body(it, main, b)
            }
        } else {
            Node(b.trace.id, LineRow(b.trace, b.trace.lines.first(), null, true, summary(b, null, true)), 0).also { node ->
                b.shape.roots.forEach { step(node, it, b) }
            }
        }
    }

    private fun step(parent: Node, s: Step, b: Build) {
        val start = s.startLine ?: return body(parent, s, b)
        val child = Node(b.trace.id, LineRow(b.trace, start, s, false, summary(b, s, false)), parent.depth + 1)
        parent.children += child
        body(child, s, b)
    }

    private fun body(node: Node, s: Step, b: Build) {
        val depth = node.depth + 1
        val items = ArrayList<Pair<Long, Any>>()
        b.shape.kids[s.id]?.forEach { items += it.start to it }
        s.notes.forEach { items += it.time to it }
        items.sortBy { it.first }
        for ((_, item) in items) {
            when (item) {
                is Step -> step(node, item, b)
                is LogLine -> node.children += Node(b.trace.id, LineRow(b.trace, item, null, false, emptyList()), depth)
            }
        }
        val end = s.endLine
        val last = when {
            end != null -> LineRow(b.trace, end, null, false, emptyList())
            s.startLine == null -> return
            s.unfinished -> PendingRow("END never came", lost = true)
            else -> PendingRow("running · " + Palette.duration(b.now - s.start), lost = false)
        }
        node.children += Node(b.trace.id, last, depth)
    }

    private fun summary(b: Build, s: Step?, group: Boolean): List<Part> {
        val trace = b.trace
        val shape = b.shape
        val parts = ArrayList<Part>()
        parts += Palette.plural(if (group) trace.lines.size else trace.blockCount(s!!, shape), "line") to Palette.GRAY
        if (s != null) {
            val end = s.end
            when {
                end != null -> {
                    val outcome = s.endLine?.outcome
                    parts += listOfNotNull(outcome ?: "END", s.endLine?.status).joinToString(" ") to Palette.outcome(outcome)
                    parts += Palette.duration(end - s.start) to Palette.GRAY
                }
                s.unfinished -> parts += "no END" to Palette.ERROR_TEXT
                else -> parts += "running " + Palette.duration(b.now - s.start) to Palette.RUNNING
            }
        }
        if (group) {
            if (shape.unfinished > 0) parts += Palette.plural(shape.unfinished, "step") + " unfinished" to Palette.ERROR_TEXT
            if (shape.running > 0 && s?.end != null) parts += Palette.plural(shape.running, "step") + " running" to Palette.RUNNING
            if (trace.count(Level.ERROR) > 0) parts += "${trace.count(Level.ERROR)} ERROR" to Palette.level(Level.ERROR)
            if (trace.count(Level.WARN) > 0) parts += "${trace.count(Level.WARN)} WARN" to Palette.level(Level.WARN)
        }
        return parts
    }

    /* ---------- open rows ---------- */

    private fun isExpanded(node: Node): Boolean {
        val u = ui[node.traceId] ?: return false
        if (node.depth == 0) return u.open
        val step = (node.row as? LineRow)?.step ?: return true
        return step.id !in u.folded
    }

    private fun toggle(node: Node) {
        val u = ui[node.traceId] ?: return
        val open = !isExpanded(node)
        if (node.depth == 0) {
            u.open = open
        } else {
            val step = (node.row as? LineRow)?.step ?: return
            if (open) u.folded -= step.id else u.folded += step.id
        }
        heights.clear()
        widths.clear()
        refresh()
    }

    private fun setAllOpen(open: Boolean) {
        for (u in ui.values) {
            u.open = open
            if (open) u.folded.clear()
        }
        heights.clear()
        widths.clear()
        refresh()
    }

    /** Lists the open rows again, keeping the selection on the same line. */
    private fun refresh() {
        val seq = selectedSeq
        restoring = true
        try {
            visible.clear()
            for (u in ui.values) {
                val node = u.node ?: continue
                visible += node
                if (u.open) addOpen(node)
            }
            tableModel.fireTableDataChanged()
            val index = if (seq == null) -1 else visible.indexOfFirst { (it.row as? LineRow)?.line?.seq == seq }
            if (index >= 0) table.selectionModel.setSelectionInterval(index, index)
            fitColumns()
            relayout()
        } finally {
            restoring = false
        }
        notifySelection()
    }

    private fun addOpen(node: Node) {
        for (child in node.children) {
            visible += child
            if (child.children.isNotEmpty() && isExpanded(child)) addOpen(child)
        }
    }

    private fun notifySelection() {
        val line = selectedLine()
        if (line?.seq != selectedSeq) {
            selectedSeq = line?.seq
            onSelect(line)
        }
    }

    /* ---------- row heights and widths ---------- */

    private fun scheduleRelayout() {
        if (!wrap || relayoutPending) return
        relayoutPending = true
        SwingUtilities.invokeLater {
            relayoutPending = false
            relayout()
        }
    }

    /** Wrapped rows get the height their message needs; unwrapped ones share one height and the widest sets the column. */
    private fun relayout() {
        val column = table.columnModel.getColumn(MESSAGE)
        if (wrap) {
            val width = column.width
            if (width != heightsWidth) {
                heights.clear()
                heightsWidth = width
            }
            val kept = IdentityHashMap<Node, Int>()
            visible.forEachIndexed { i, node ->
                val h = heights[node] ?: message.heightFor(node, width)
                kept[node] = h
                if (table.getRowHeight(i) != h) table.setRowHeight(i, h)
            }
            heights.clear()
            heights.putAll(kept)
        } else {
            table.rowHeight = message.rowHeight
            val kept = IdentityHashMap<Node, Int>()
            for (node in visible) kept[node] = widths[node] ?: message.widthFor(node)
            widths.clear()
            widths.putAll(kept)
            column.preferredWidth = maxOf(kept.values.maxOrNull() ?: 0, JBUI.scale(200))
        }
    }

    /* ---------- rendering ---------- */

    private fun TextCell.configureMessage(node: Node?) {
        wrap = this@TraceTree.wrap
        arrowSlot = true
        indent = TextCell.indent(node?.depth ?: 0)
        val expanded = node != null && node.children.isNotEmpty() && isExpanded(node)
        arrow = when {
            node == null || node.children.isEmpty() -> null
            expanded -> UIUtil.getTreeExpandedIcon()
            else -> UIUtil.getTreeCollapsedIcon()
        }
        parts = when (val r = node?.row) {
            is PendingRow -> listOf(r.text to if (r.lost) Palette.ERROR_ITALIC else Palette.RUNNING_ITALIC)
            is LineRow -> lineParts(r) + if (r.group || (r.step != null && !expanded)) r.summary.map { (t, a) -> "   $t" to a } else emptyList()
            else -> emptyList()
        }
    }

    private fun lineParts(r: LineRow): List<Part> {
        val l = r.line
        if (r.group && r.step == null) return listOf("trace " + r.trace.id.take(8) to Palette.BOLD)
        val parts = ArrayList<Part>()
        parts += l.title to if (l.event != null) Palette.BOLD else Palette.PLAIN
        l.phase?.let { parts += " $it" to if (it.name == "START") Palette.START else Palette.END }
        for ((key, value) in l.fields) {
            if (key in HIDDEN_FIELDS) continue
            parts += " $key=" to Palette.GRAY
            parts += value to Palette.value(key, value)
        }
        if (r.group) {
            parts += " user=" to Palette.GRAY
            parts += (l.user ?: "-") to Palette.PLAIN
            parts += " trace=" to Palette.GRAY
            parts += r.trace.id.take(8) to Palette.PLAIN
        }
        return parts
    }

    private fun lineOf(value: Any?): LogLine? = ((value as? Node)?.row as? LineRow)?.line

    /* ---------- selection and scrolling ---------- */

    private fun bindKey(key: Int, name: String, action: (Node) -> Unit) {
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name)
        table.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                visible.getOrNull(table.selectedRow)?.let(action)
            }
        })
    }

    private fun selectRelative(delta: Int) {
        val i = (table.selectedRow + delta).coerceIn(0, visible.size - 1)
        select(i)
    }

    private fun selectParent(node: Node) {
        val i = table.selectedRow
        for (j in i - 1 downTo 0) {
            if (visible[j].traceId == node.traceId && visible[j].depth < node.depth) return select(j)
        }
    }

    private fun select(i: Int) {
        if (i !in visible.indices) return
        table.selectionModel.setSelectionInterval(i, i)
        scrollToRow(i)
    }

    /** Brings a row into view without scrolling sideways. */
    private fun scrollToRow(i: Int) {
        if (i !in visible.indices) return
        val cell = table.getCellRect(i, 0, true)
        val view = table.visibleRect
        table.scrollRectToVisible(Rectangle(view.x, cell.y, maxOf(view.width, 1), cell.height))
    }

    /* ---------- column widths ---------- */

    /** Sizes the Time, Level and App columns to their title and their widest text; the Message column takes the rest. */
    private fun fitColumns() {
        fitColumn(TIME, listOf(Palette.time(0) to Palette.GRAY))
        fitColumn(LEVEL, Level.entries.map { it.name to Palette.level(it) })
        fitColumn(APP, model.apps.map { it to Palette.app(model.appIndex(it)) })
    }

    private fun fitColumn(index: Int, texts: List<Part>) {
        val column = table.columnModel.getColumn(index)
        val title = table.tableHeader.defaultRenderer.getTableCellRendererComponent(table, COLUMNS[index], false, false, -1, index)
        val width = maxOf(message.widthOf(texts), title.preferredSize.width)
        if (column.minWidth == width && column.maxWidth == width) return
        column.minWidth = 0
        column.maxWidth = Int.MAX_VALUE
        column.preferredWidth = width
        column.minWidth = width
        column.maxWidth = width
    }

    private companion object {
        val COLUMNS = arrayOf("Time", "Level", "App", "Message")
        const val TIME = 0
        const val LEVEL = 1
        const val APP = 2
        const val MESSAGE = 3
        val HIDDEN_FIELDS = setOf("startTime")
    }
}
