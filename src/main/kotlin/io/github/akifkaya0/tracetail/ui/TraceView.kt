package io.github.akifkaya0.tracetail.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import io.github.akifkaya0.tracetail.TraceFeed
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.Trace
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The requests as a tree. Beside it, the selected lines (their JSON and stack traces) and the
 * selected requests as sequence diagrams, one under the other.
 */
class TraceView(
    project: Project,
    private val feed: TraceFeed,
    include: (Trace) -> Boolean,
    showInFlat: ((LogLine) -> Unit)? = null,
) : Disposable {

    private val details: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
    private val sequences = ArrayList<SequencePanel>()
    private val sequenceBox = JPanel(VerticalLayout(JBUI.scale(24)))
    private val tree = TraceTree(project, feed.model, include, ::select, showInFlat)
    private var ticks = 0

    val component: JComponent = OnePixelSplitter(false, 0.6f).apply {
        firstComponent = tree.component
        secondComponent = JBTabbedPane().apply {
            addTab("Line", details.component)
            addTab("Sequence", sequenceTab())
        }
    }

    /** Scroll to the end, expand all and collapse all, for the view's toolbar. */
    val treeActions: List<AnAction> = CommonActionsManager.getInstance().let {
        listOf(ScrollToEndAction(), it.createExpandAllAction(tree.expander, tree.component), it.createCollapseAllAction(tree.expander, tree.component))
    }

    init {
        Disposer.register(this, details)
        showSequences(emptyList())
        softWraps(feed.softWraps)
        feed.subscribe(this, object : TraceFeed.Listener {
            override fun softWrapsChanged(on: Boolean) = softWraps(on)

            override fun changed(change: Change) {
                tree.apply(change)
                val shown = shownIds()
                if (shown.isNotEmpty() && (change.reset || change.changed.any { it.id in shown })) showSequences(shown)
            }

            override fun ticked() {
                if (++ticks % REFRESH_EVERY_TICKS != 0) return
                tree.refreshRunning()
                // the running steps' times keep growing
                if (sequences.any { it.running }) showSequences(shownIds())
            }
        })
    }

    override fun dispose() = Unit

    private fun softWraps(on: Boolean) {
        tree.setWrap(on)
        details.setSoftWraps(on)
    }

    private fun sequenceTab(): JComponent {
        val copy = object : DumbAwareAction("Copy as Mermaid", "Copy the diagram as Mermaid text", AllIcons.Actions.Copy) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = shownIds().isNotEmpty()
            }

            override fun actionPerformed(e: AnActionEvent) {
                val text = sequences.mapNotNull { it.mermaid }.joinToString("\n\n")
                if (text.isNotEmpty()) CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        }
        val scroll = ScrollPaneFactory.createScrollPane(sequenceBox, true)
        val toolbar = ActionManager.getInstance().createActionToolbar("TraceTailSequence", DefaultActionGroup(copy), true)
        toolbar.targetComponent = scroll
        return SimpleToolWindowPanel(true, true).apply {
            this.toolbar = toolbar.component
            setContent(scroll)
        }
    }

    /** Like a console's: shows the newest rows, and a tree scrolled to its end keeps following new ones. */
    private inner class ScrollToEndAction :
        DumbAwareAction("Scroll to the End", "Show the newest lines and keep following them", AllIcons.RunConfigurations.Scroll_down) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !tree.atBottom()
        }

        override fun actionPerformed(e: AnActionEvent) = tree.scrollToBottom()
    }

    private fun select(lines: List<LogLine>) {
        details.clear()
        lines.forEachIndexed { i, line ->
            if (i > 0) details.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
            details.print(line.prettyJson() + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
            line.stackTrace?.let { details.print("\n" + it + "\n", ConsoleViewContentType.ERROR_OUTPUT) }
        }
        val ids = lines.mapNotNull { it.trace }.distinct()
        if (ids != shownIds()) showSequences(ids)
    }

    private fun shownIds(): List<String> = sequences.mapNotNull { it.trace?.id }

    /** Draws the requests one under the other; with none, the diagram asks for a line to be selected. */
    private fun showSequences(ids: List<String>) {
        val traces: List<Trace?> = ids.mapNotNull { feed.model.traces[it] }.ifEmpty { listOf(null) }
        while (sequences.size < traces.size) sequences += SequencePanel(feed.model).also { sequenceBox.add(it) }
        while (sequences.size > traces.size) sequenceBox.remove(sequences.removeAt(sequences.lastIndex))
        sequences.zip(traces).forEach { (panel, trace) -> panel.show(trace) }
        sequenceBox.revalidate()
        sequenceBox.repaint()
    }

    private companion object {
        /** Running steps are redrawn every half second. */
        const val REFRESH_EVERY_TICKS = 500 / TraceFeed.TICK_MILLIS
    }
}
