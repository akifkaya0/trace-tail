package io.github.akifkaya0.tracetail.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import io.github.akifkaya0.tracetail.TraceFeed
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.Trace
import javax.swing.JComponent

/** The requests as a tree, with the selected line's JSON and stack trace beside it. */
class TraceView(project: Project, feed: TraceFeed, include: (Trace) -> Boolean) : Disposable {

    private val details: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
    private val tree = TraceTree(project, feed.model, include, ::showDetails)
    private var ticks = 0

    val component: JComponent = OnePixelSplitter(false, 0.7f).apply {
        firstComponent = tree.component
        secondComponent = details.component
    }

    /** Expand all and collapse all, for the view's toolbar. */
    val treeActions: List<AnAction> = CommonActionsManager.getInstance().let {
        listOf(it.createExpandAllAction(tree.expander, tree.component), it.createCollapseAllAction(tree.expander, tree.component))
    }

    init {
        Disposer.register(this, details)
        feed.subscribe(this, object : TraceFeed.Listener {
            override fun changed(change: Change) = tree.apply(change)
            override fun ticked() {
                if (++ticks % REFRESH_EVERY_TICKS == 0) tree.refreshRunning()
            }
        })
    }

    override fun dispose() = Unit

    private fun showDetails(line: LogLine?) {
        details.clear()
        if (line == null) return
        details.print(line.prettyJson() + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
        line.stackTrace?.let { details.print("\n" + it + "\n", ConsoleViewContentType.ERROR_OUTPUT) }
    }

    private companion object {
        /** Running steps are redrawn every half second. */
        const val REFRESH_EVERY_TICKS = 500 / TraceFeed.TICK_MILLIS
    }
}
