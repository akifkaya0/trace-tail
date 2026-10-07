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
import io.github.akifkaya0.tracetail.TraceFeed
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.Trace
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

/**
 * The requests as a tree. Beside it, the selected line (its JSON and stack trace) and the
 * selected request as a sequence diagram.
 */
class TraceView(project: Project, private val feed: TraceFeed, include: (Trace) -> Boolean) : Disposable {

    private val details: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
    private val sequence = SequencePanel(feed.model)
    private val tree = TraceTree(project, feed.model, include, ::select)
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
        softWraps(feed.softWraps)
        feed.subscribe(this, object : TraceFeed.Listener {
            override fun softWrapsChanged(on: Boolean) = softWraps(on)

            override fun changed(change: Change) {
                tree.apply(change)
                val shown = sequence.trace ?: return
                if (change.reset || change.changed.any { it.id == shown.id }) sequence.show(feed.model.traces[shown.id])
            }

            override fun ticked() {
                if (++ticks % REFRESH_EVERY_TICKS != 0) return
                tree.refreshRunning()
                // the running steps' times keep growing
                if (sequence.running) sequence.show(sequence.trace?.let { feed.model.traces[it.id] })
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
                e.presentation.isEnabled = sequence.trace != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                sequence.mermaid?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
            }
        }
        val scroll = ScrollPaneFactory.createScrollPane(sequence, true)
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

    private fun select(line: LogLine?) {
        details.clear()
        if (line != null) {
            details.print(line.prettyJson() + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
            line.stackTrace?.let { details.print("\n" + it + "\n", ConsoleViewContentType.ERROR_OUTPUT) }
        }
        val trace = line?.trace?.let { feed.model.traces[it] }
        if (trace?.id != sequence.trace?.id || trace == null) sequence.show(trace)
    }

    private companion object {
        /** Running steps are redrawn every half second. */
        const val REFRESH_EVERY_TICKS = 500 / TraceFeed.TICK_MILLIS
    }
}
