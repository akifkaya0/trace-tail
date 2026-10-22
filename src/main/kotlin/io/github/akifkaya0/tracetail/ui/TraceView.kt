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
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.WindowWrapper
import com.intellij.openapi.ui.WindowWrapperBuilder
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
import io.github.akifkaya0.tracetail.model.TraceModel
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The requests, one row each. Beside them, in tabs: the selected requests open into their steps,
 * with the selected lines' JSON and stack traces on their right (Steps), and the selected requests
 * as sequence diagrams, one under the other (Sequence).
 */
class TraceView(
    project: Project,
    private val feed: TraceFeed,
    include: (Trace) -> Boolean,
    showInFlat: ((LogLine) -> Unit)? = null,
) : Disposable {

    private val details: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
    private val sequences = Sequences(feed.model)

    /** The ids of the requests selected in the list, which Steps shows. */
    private var selected: Set<String> = emptySet()
    private val requests = TraceTree(project, feed.model, include, ::selectRequests, showInFlat, steps = false)
    private val steps = TraceTree(project, feed.model, { it.id in selected }, ::selectLines, showInFlat, steps = true)
    private var ticks = 0

    // the IDE remembers where the dividers were dragged, for this view and every one opened later
    val component: JComponent = OnePixelSplitter(false, "tracetail.requestsSplit", 0.4f).apply {
        firstComponent = requests.component
        secondComponent = JBTabbedPane().apply {
            addTab("Steps", OnePixelSplitter(false, "tracetail.stepsSplit", 0.6f).apply {
                firstComponent = steps.component
                // closed until Show Line JSON opens it; the splitter gives the steps the whole width meanwhile
                secondComponent = details.component.apply { isVisible = false }
            })
            addTab("Sequence", sequenceTab(project))
        }
    }

    /** Scroll to the end of the requests, expand all and collapse all of Steps, and open the line's JSON, for the view's toolbar. */
    val treeActions: List<AnAction> = CommonActionsManager.getInstance().let {
        listOf(
            ScrollToEndAction(),
            it.createExpandAllAction(steps.expander, steps.component),
            it.createCollapseAllAction(steps.expander, steps.component),
            ShowDetailsAction(),
        )
    }

    /** Set when requests changed while the view was off screen; it starts over from the model when it shows again. */
    private var stale = false

    init {
        Disposer.register(this, details)
        sequences.show(emptyList())
        softWraps(feed.softWraps)
        component.addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && component.isShowing && stale) {
                stale = false
                apply(feed.model.snapshot())
            }
        }
        feed.subscribe(this, object : TraceFeed.Listener {
            override fun softWrapsChanged(on: Boolean) = softWraps(on)

            override fun changed(change: Change) {
                // building rows no one sees costs as much as building those in view
                if (component.isShowing) apply(change) else stale = true
            }

            override fun ticked() {
                if (!component.isShowing || ++ticks % REFRESH_EVERY_TICKS != 0) return
                requests.refreshRunning()
                steps.refreshRunning()
                // the running steps' times keep growing
                if (sequences.running) sequences.show(sequences.ids)
            }
        })
    }

    override fun dispose() = Unit

    private fun apply(change: Change) {
        requests.apply(change)
        steps.apply(change)
        val shown = sequences.ids
        if (shown.isNotEmpty() && (change.reset || change.changed.any { it.id in shown })) sequences.show(shown)
    }

    private fun softWraps(on: Boolean) {
        requests.setWrap(on)
        steps.setWrap(on)
        details.setSoftWraps(on)
    }

    private fun sequenceTab(project: Project): JComponent {
        val copy = object : DumbAwareAction("Copy as Mermaid", "Copy the diagram as Mermaid text", AllIcons.Actions.Copy) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = sequences.ids.isNotEmpty()
            }

            override fun actionPerformed(e: AnActionEvent) {
                val text = sequences.mermaid
                if (text.isNotEmpty()) CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        }
        val open = object : DumbAwareAction("Open in Window", "Show the diagram in a window of its own", AllIcons.Actions.MoveToWindow) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = sequences.ids.isNotEmpty()
            }

            override fun actionPerformed(e: AnActionEvent) = openWindow(project, sequences.ids)
        }
        val scroll = ScrollPaneFactory.createScrollPane(sequences.component, true)
        val toolbar = ActionManager.getInstance().createActionToolbar("TraceTailSequence", DefaultActionGroup(copy, open), true)
        toolbar.targetComponent = scroll
        return SimpleToolWindowPanel(true, true).apply {
            this.toolbar = toolbar.component
            setContent(scroll)
        }
    }

    /** Like a console's: shows the newest requests, and a list scrolled to its end keeps following new ones. */
    private inner class ScrollToEndAction :
        DumbAwareAction("Scroll to the End", "Show the newest lines and keep following them", AllIcons.RunConfigurations.Scroll_down) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !requests.atBottom()
        }

        override fun actionPerformed(e: AnActionEvent) = requests.scrollToBottom()
    }

    /** Opens and closes the selected lines' JSON beside the steps. */
    private inner class ShowDetailsAction :
        DumbAwareToggleAction("Show Line JSON", "Show the selected lines' JSON and stack traces beside the steps", AllIcons.Actions.PreviewDetails) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = details.component.isVisible

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            details.component.isVisible = state
            details.component.parent?.revalidate()
        }
    }

    /** Opens the selected requests in Steps and draws them in Sequence. */
    private fun selectRequests(lines: List<LogLine>) {
        selected = lines.mapNotNullTo(LinkedHashSet()) { it.trace }
        steps.show(selected.mapNotNull { feed.model.traces[it] })
        selectLines(steps.selectedLines())
        val ids = selected.toList()
        if (ids != sequences.ids) sequences.show(ids)
    }

    /** Beside the steps, the JSON of the lines selected in Steps, or with none, of the rows selected in the requests. */
    private fun selectLines(lines: List<LogLine>) {
        details.clear()
        lines.ifEmpty { requests.selectedLines() }.forEachIndexed { i, line ->
            if (i > 0) details.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
            details.print(line.prettyJson() + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
            line.stackTrace?.let { details.print("\n" + it + "\n", ConsoleViewContentType.ERROR_OUTPUT) }
        }
    }

    /** Draws the requests in a window of their own, which keeps redrawing them as they go on until it is closed. */
    private fun openWindow(project: Project, ids: List<String>) {
        val shown = Sequences(feed.model)
        val window = WindowWrapperBuilder(WindowWrapper.Mode.FRAME, ScrollPaneFactory.createScrollPane(shown.component, true))
            .setProject(project)
            .setTitle("Sequence: " + ids.joinToString())
            .setDimensionServiceKey("tracetail.sequenceWindow")
            .build()
        var ticks = 0
        // closing the window disposes it, which unsubscribes; the snapshot that subscribing sends draws the requests
        feed.subscribe(window, object : TraceFeed.Listener {
            override fun changed(change: Change) {
                if (change.reset || change.changed.any { it.id in ids }) shown.show(ids)
            }

            override fun ticked() {
                if (++ticks % REFRESH_EVERY_TICKS == 0 && shown.running) shown.show(ids)
            }
        })
        window.show()
    }

    private companion object {
        /** Running steps are redrawn every half second. */
        const val REFRESH_EVERY_TICKS = 500 / TraceFeed.TICK_MILLIS
    }
}

/** Requests' diagrams one under the other; with none, the diagram asks for a request to be selected. */
private class Sequences(private val model: TraceModel) {
    private val panels = ArrayList<SequencePanel>()
    val component = JPanel(VerticalLayout(JBUI.scale(24)))

    /** The requests shown. */
    val ids: List<String> get() = panels.mapNotNull { it.trace?.id }

    val running: Boolean get() = panels.any { it.running }

    val mermaid: String get() = panels.mapNotNull { it.mermaid }.joinToString("\n\n")

    fun show(ids: List<String>) {
        val traces: List<Trace?> = ids.mapNotNull { model.traces[it] }.ifEmpty { listOf(null) }
        while (panels.size < traces.size) panels += SequencePanel(model).also { component.add(it) }
        while (panels.size > traces.size) component.remove(panels.removeAt(panels.lastIndex))
        panels.zip(traces).forEach { (panel, trace) -> panel.show(trace) }
        component.revalidate()
        component.repaint()
    }
}
