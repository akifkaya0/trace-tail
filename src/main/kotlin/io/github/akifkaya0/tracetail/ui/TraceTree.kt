package io.github.akifkaya0.tracetail.ui

import com.intellij.ide.TreeExpander
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.dualView.TreeTableView
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.Level
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.Step
import io.github.akifkaya0.tracetail.model.Trace
import io.github.akifkaya0.tracetail.model.TraceModel
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

private typealias Part = Pair<String, SimpleTextAttributes>

/**
 * A row of the tree. A row with a [step] is a START line whose children are the step's lines. [bar]
 * places the row on its request's time line.
 */
internal class LineRow(
    val trace: Trace,
    val line: LogLine,
    val step: Step?,
    val group: Boolean,
    val summary: List<Part>,
    val bar: Bar?,
)

/** Stands in for an END line that has not come, or never will. */
internal class PendingRow(val text: String, val lost: Boolean)

/**
 * The requests as a tree: one row per request, opening into its steps, nested by span.id and
 * parent.id. Requests the reader opened, and steps they folded, stay that way while lines arrive.
 */
internal class TraceTree(
    private val project: Project,
    private val model: TraceModel,
    /** Which requests this tree shows. */
    private val include: (Trace) -> Boolean,
    private val onSelect: (LogLine?) -> Unit,
) {

    /** The reader's choices for one request, kept across rebuilds. */
    private class TraceUi(val node: DefaultMutableTreeNode) {
        var open = false
        val folded = HashSet<String?>()
        var wasRunning = false
    }

    private val ui = LinkedHashMap<String, TraceUi>()
    private val root = DefaultMutableTreeNode()
    private val treeModel = ListTreeTableModelOnColumns(
        root,
        arrayOf<ColumnInfo<*, *>>(
            column("Time") { append(Palette.time(it.line.time), Palette.GRAY) },
            column("Level") { append(it.line.level.name, Palette.level(it.line.level)) },
            column("App") { append(it.line.app, Palette.app(model.appIndex(it.line.app))) },
            object : ColumnInfo<DefaultMutableTreeNode, Any?>("Timeline") {
                private val renderer = TimelineRenderer()
                override fun valueOf(item: DefaultMutableTreeNode?): Any? = item?.userObject
                override fun getRenderer(item: DefaultMutableTreeNode?): TableCellRenderer = renderer
            },
            TreeColumnInfo("Message"),
        ),
    )
    private val table = TreeTableView(treeModel)
    private val scroll: JScrollPane = ScrollPaneFactory.createScrollPane(table)
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
        table.setRootVisible(false)
        table.tree.showsRootHandles = true
        table.setTreeCellRenderer(MessageRenderer())
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.setShowGrid(false)
        fixWidth(0, 96)
        fixWidth(1, 56)
        fixWidth(2, 140)
        fixWidth(3, 160)
        table.tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = remember(event.path, true)
            override fun treeCollapsed(event: TreeExpansionEvent) = remember(event.path, false)
        })
        // F4 and this menu open the class that wrote the line
        PopupHandler.installPopupMenu(
            table,
            DefaultActionGroup(ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE)),
            "TraceTailTree",
        )
        table.tree.addTreeSelectionListener {
            if (restoring) return@addTreeSelectionListener
            val line = selectedLine()
            if (line?.seq != selectedSeq) {
                selectedSeq = line?.seq
                onSelect(line)
            }
        }
    }

    fun selectedLine(): LogLine? = ((table.tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? LineRow)?.line

    fun apply(change: Change) {
        val changed = change.changed.filter(include)
        if (!change.reset && changed.isEmpty() && change.removed.isEmpty()) return
        val follow = atBottom()
        keepingSelection {
            if (change.reset) {
                val old = HashMap(ui)
                ui.clear()
                root.removeAllChildren()
                for (trace in changed) {
                    val u = TraceUi(DefaultMutableTreeNode())
                    old[trace.id]?.let { u.open = it.open; u.folded += it.folded }
                    ui[trace.id] = u
                    root.add(u.node)
                }
                treeModel.reload()
                changed.forEach(::rebuild)
            } else {
                for (trace in change.removed) {
                    val u = ui.remove(trace.id) ?: continue
                    val index = root.getIndex(u.node)
                    if (index >= 0) {
                        root.remove(index)
                        treeModel.nodesWereRemoved(root, intArrayOf(index), arrayOf(u.node))
                    }
                }
                for (trace in changed) {
                    if (trace.id !in ui) {
                        val u = TraceUi(DefaultMutableTreeNode())
                        ui[trace.id] = u
                        root.add(u.node)
                        treeModel.nodesWereInserted(root, intArrayOf(root.childCount - 1))
                    }
                    rebuild(trace)
                }
            }
        }
        if (follow && changed.isNotEmpty()) scrollToBottom()
    }

    /** Redraws the requests with running steps, whose times keep growing. */
    fun refreshRunning() {
        val running = ui.filterValues { it.wasRunning }.keys.mapNotNull { model.traces[it] }
        if (running.isNotEmpty()) keepingSelection { running.forEach(::rebuild) }
    }

    private fun rebuild(trace: Trace) {
        val u = ui[trace.id] ?: return
        val now = System.currentTimeMillis()
        val shape = trace.analyse(now)
        build(u.node, Build(trace, shape, now, TraceWindow(trace, now)))
        treeModel.nodeStructureChanged(u.node)
        restore(u)
        u.wasRunning = shape.running > 0
    }

    /* ---------- building a request's rows ---------- */

    /** What building one request's rows needs. */
    private class Build(val trace: Trace, val shape: Trace.Shape, val now: Long, val window: TraceWindow)

    private fun build(node: DefaultMutableTreeNode, b: Build) {
        node.removeAllChildren()
        val main = b.shape.roots.singleOrNull()?.takeIf { it.startLine != null }
        if (main != null) {
            node.userObject = LineRow(b.trace, main.startLine!!, main, true, summary(b, main, true), b.window.step(main, color(main)))
            body(node, main, b)
        } else {
            val first = b.trace.lines.first()
            val bar = b.window.whole(b.shape.running > 0, Palette.appColor(model.appIndex(first.app)))
            node.userObject = LineRow(b.trace, first, null, true, summary(b, null, true), bar)
            b.shape.roots.forEach { step(node, it, b) }
        }
    }

    private fun step(node: DefaultMutableTreeNode, s: Step, b: Build) {
        val start = s.startLine ?: return body(node, s, b)
        val child = DefaultMutableTreeNode(LineRow(b.trace, start, s, false, summary(b, s, false), b.window.step(s, color(s))))
        node.add(child)
        body(child, s, b)
    }

    private fun body(node: DefaultMutableTreeNode, s: Step, b: Build) {
        val items = ArrayList<Pair<Long, Any>>()
        b.shape.kids[s.id]?.forEach { items += it.start to it }
        s.notes.forEach { items += it.time to it }
        items.sortBy { it.first }
        for ((_, item) in items) {
            when (item) {
                is Step -> step(node, item, b)
                is LogLine -> node.add(DefaultMutableTreeNode(LineRow(b.trace, item, null, false, emptyList(), b.window.mark(item))))
            }
        }
        val end = s.endLine
        when {
            end != null -> node.add(DefaultMutableTreeNode(LineRow(b.trace, end, null, false, emptyList(), null)))
            s.startLine == null -> Unit
            s.unfinished -> node.add(DefaultMutableTreeNode(PendingRow("END never came", lost = true)))
            else -> node.add(DefaultMutableTreeNode(PendingRow("running · " + Palette.duration(b.now - s.start), lost = false)))
        }
    }

    private fun color(s: Step) = Palette.appColor(model.appIndex(s.app ?: ""))

    private fun summary(b: Build, s: Step?, group: Boolean): List<Part> {
        val trace = b.trace
        val shape = b.shape
        val now = b.now
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
                else -> parts += "running " + Palette.duration(now - s.start) to Palette.RUNNING
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

    /* ---------- open and folded state ---------- */

    private fun remember(path: TreePath, open: Boolean) {
        if (restoring || path.pathCount < 2) return
        val traceNode = path.getPathComponent(1) as? DefaultMutableTreeNode ?: return
        val u = (traceNode.userObject as? LineRow)?.let { ui[it.trace.id] } ?: return
        val node = path.lastPathComponent as DefaultMutableTreeNode
        if (node === traceNode) {
            u.open = open
            if (open) restore(u)   // the steps inside open as the reader left them
        } else {
            val step = (node.userObject as? LineRow)?.step ?: return
            if (open) u.folded -= step.id else u.folded += step.id
        }
    }

    private fun restore(u: TraceUi) {
        val wasRestoring = restoring
        restoring = true
        try {
            val tree = table.tree
            val path = TreePath(u.node.path)
            if (!u.open) {
                tree.collapsePath(path)
                return
            }
            tree.expandPath(path)
            fun walk(node: DefaultMutableTreeNode) {
                for (i in 0 until node.childCount) {
                    val child = node.getChildAt(i) as DefaultMutableTreeNode
                    if (child.childCount == 0) continue
                    val step = (child.userObject as? LineRow)?.step
                    if (step != null && step.id in u.folded) continue
                    tree.expandPath(TreePath(child.path))
                    walk(child)
                }
            }
            walk(u.node)
        } finally {
            restoring = wasRestoring
        }
    }

    private fun setAllOpen(open: Boolean) = keepingSelection {
        for (u in ui.values) {
            u.open = open
            if (open) u.folded.clear()
            restore(u)
        }
    }

    /* ---------- selection and scrolling ---------- */

    /** A structure change clears the table's selection; this puts it back on the same line. */
    private fun keepingSelection(update: () -> Unit) {
        val seq = selectedSeq
        val traceId = selectedLine()?.trace
        restoring = true
        try {
            update()
            val node = traceId?.let { ui[it]?.node }?.let { find(it, seq) }
            if (node != null) {
                val path = TreePath(node.path)
                if (table.tree.isVisible(path)) table.tree.selectionPath = path
            }
        } finally {
            restoring = false
        }
        if (selectedLine()?.seq != seq) {
            selectedSeq = selectedLine()?.seq
            onSelect(selectedLine())
        }
    }

    private fun find(node: DefaultMutableTreeNode, seq: Long?): DefaultMutableTreeNode? {
        if (seq == null) return null
        if ((node.userObject as? LineRow)?.line?.seq == seq) return node
        for (i in 0 until node.childCount) find(node.getChildAt(i) as DefaultMutableTreeNode, seq)?.let { return it }
        return null
    }

    private fun atBottom(): Boolean {
        val bar = scroll.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(8)
    }

    private fun scrollToBottom() {
        val last = table.rowCount - 1
        if (last >= 0) table.scrollRectToVisible(table.getCellRect(last, 0, true))
    }

    /* ---------- columns and rendering ---------- */

    private fun fixWidth(index: Int, width: Int) {
        val column = table.columnModel.getColumn(index)
        column.preferredWidth = JBUI.scale(width)
        column.minWidth = JBUI.scale(width)
        column.maxWidth = JBUI.scale(width)
    }

    private fun column(name: String, draw: SimpleColoredComponent.(LineRow) -> Unit) =
        object : ColumnInfo<DefaultMutableTreeNode, Any?>(name) {
            private val renderer = object : ColoredTableCellRenderer() {
                override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                    (value as? LineRow)?.let { draw(it) }
                }
            }

            override fun valueOf(item: DefaultMutableTreeNode?): Any? = item?.userObject
            override fun getRenderer(item: DefaultMutableTreeNode?): TableCellRenderer = renderer
        }

    private class MessageRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val r = (value as? DefaultMutableTreeNode)?.userObject) {
                is PendingRow -> append(r.text, if (r.lost) Palette.ERROR_ITALIC else Palette.RUNNING_ITALIC)
                is LineRow -> {
                    line(r)
                    if (r.group || (r.step != null && !expanded)) {
                        for ((text, attributes) in r.summary) append("   $text", attributes)
                    }
                }
            }
        }

        private fun line(r: LineRow) {
            val l = r.line
            if (r.group && r.step == null) {
                append("trace " + r.trace.id.take(8), Palette.BOLD)
                return
            }
            append(l.title, if (l.event != null) Palette.BOLD else Palette.PLAIN)
            l.phase?.let { append(" $it", if (it.name == "START") Palette.START else Palette.END) }
            for ((key, value) in l.fields) {
                if (key in HIDDEN_FIELDS) continue
                append(" $key=", Palette.GRAY)
                append(value, Palette.value(key, value))
            }
            if (r.group) {
                append(" user=", Palette.GRAY)
                append(l.user ?: "-", Palette.PLAIN)
                append(" trace=", Palette.GRAY)
                append(r.trace.id.take(8), Palette.PLAIN)
            }
        }
    }

    private companion object {
        val HIDDEN_FIELDS = setOf("startTime")
    }
}
