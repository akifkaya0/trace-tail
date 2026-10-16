package io.github.akifkaya0.tracetail.ui

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.actions.ScrollToTheEndToolbarAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import io.github.akifkaya0.tracetail.TraceFeed
import io.github.akifkaya0.tracetail.TraceTailServer
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.Level
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.TraceModel
import java.awt.BorderLayout
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The tool window's tabs: Tree (every request, from every app), Flat (every line in arrival order)
 * and Raw (the JSON as received).
 */
class TraceTailPanel(private val project: Project, private val toolWindow: ToolWindow) : Disposable {

    /** A tab's status line, and what it counts. */
    private class Status(val label: JBLabel, val count: () -> String)

    private val server = project.service<TraceTailServer>()
    private val feed = project.service<TraceFeed>()
    private val flat = FlatConsole(project, this, feed.model)
    private val raw = LogView(project, this, { listOf(it.json + "\n" to ConsoleViewContentType.NORMAL_OUTPUT) }, folds = false)
    private val statusLines = mutableListOf<Status>()

    init {
        val view = TraceView(project, feed, { true }, ::showInFlat)
        Disposer.register(this, view)
        val model = feed.model
        val shared = listOf(PauseAction(), ClearAction(), LevelGroup(), SoftWrapAction(feed))
        addTab("Tree", view.component, shared + view.treeActions) {
            Palette.plural(model.traces.size, "trace") + " · " + Palette.plural(model.traceLines, "line")
        }
        addTab("Flat", flat.component, shared + ScrollToTheEndToolbarAction(flat.editor)) { Palette.plural(flat.lineCount, "line") }
        addTab("Raw", raw.component, shared + ScrollToTheEndToolbarAction(raw.editor)) { Palette.plural(raw.lineCount, "line") }
        softWraps(feed.softWraps)
        feed.subscribe(this, object : TraceFeed.Listener {
            override fun changed(change: Change) = print(change)
            override fun ticked() = updateStatus()
            override fun softWrapsChanged(on: Boolean) = softWraps(on)
        })
    }

    override fun dispose() = Unit

    private fun addTab(title: String, component: JComponent, actions: List<AnAction>, count: () -> String) {
        val toolbar = ActionManager.getInstance().createActionToolbar("TraceTail$title", DefaultActionGroup(actions), false)
        toolbar.targetComponent = component
        val status = JBLabel().apply {
            border = JBUI.Borders.empty(2, 8)
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
        statusLines += Status(status, count)
        val body = JPanel(BorderLayout()).apply {
            add(component, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        val panel = SimpleToolWindowPanel(false, true).apply {
            this.toolbar = toolbar.component
            setContent(body)
        }
        val content = ContentFactory.getInstance().createContent(panel, title, false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }

    /** Opens the Flat tab on the line, with its request focused. */
    private fun showInFlat(line: LogLine) {
        val contents = toolWindow.contentManager
        contents.findContent("Flat")?.let { contents.setSelectedContent(it) }
        // once the tab is laid out, so that the view can scroll to the line
        SwingUtilities.invokeLater { flat.show(line) }
    }

    private fun softWraps(on: Boolean) {
        flat.setSoftWraps(on)
        raw.setSoftWraps(on)
    }

    private fun print(change: Change) {
        flat.print(change)
        raw.show(change)
    }

    /** Each tab counts the lines it shows; the port, the apps and the limit are in the tooltip. */
    private fun updateStatus() {
        val s = server.stats()
        val capped = feed.model.capped
        val state = buildString {
            if (feed.paused) append(" · paused · ${String.format(Locale.ROOT, "%,d", server.waiting())} waiting")
            if (capped != null) append(" · limit reached")
        }
        val tooltip = buildString {
            append("<html>Listening on port ${s.port}<br>${Palette.plural(s.connections, "app")} connected")
            append("<br>Each run keeps up to ${String.format(Locale.ROOT, "%,d", TraceFeed.runLimit() / 1024)} KB, the console's limit")
            if (capped != null) append("<br>All runs hold ${String.format(Locale.ROOT, "%,d", TraceModel.LINES_MAX)} lines: the oldest lines of $capped go")
            append("</html>")
        }
        for (status in statusLines) {
            status.label.text = status.count() + state
            status.label.toolTipText = tooltip
        }
    }

    /* ---------- actions ---------- */

    private inner class PauseAction : ToggleAction("Pause", "Stop taking new lines; they wait until resumed", AllIcons.Actions.Pause), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = feed.paused
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            feed.paused = state
            updateStatus()
        }
    }

    private inner class ClearAction : DumbAwareAction("Clear", "Remove the lines shown so far", AllIcons.Actions.GC) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = feed.clear()
    }

    /** One submenu for all apps and one for each app, each offering the four levels. */
    private inner class LevelGroup : ActionGroup("Level", "Hide the lines below a level", AllIcons.General.Filter), DumbAware {
        init {
            templatePresentation.isPopupGroup = true
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun getChildren(e: AnActionEvent?): Array<AnAction> =
            (listOf<String?>(null) + feed.model.apps).map { app ->
                DefaultActionGroup(app ?: "All apps", true).apply { Level.entries.forEach { add(LevelAction(app, it)) } }
            }.toTypedArray()
    }

    private inner class LevelAction(private val app: String?, private val level: Level) : ToggleAction(level.name), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = feed.model.isLevel(app, level)
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) feed.setLevel(app, level)
        }
    }
}
