package io.github.akifkaya0.tracetail.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import io.github.akifkaya0.tracetail.TraceTailServer
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.Level
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.TraceModel
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * The tool window's tabs: Tree (requests and their steps, with the selected line's details beside
 * it), Flat (every line in arrival order) and Raw (the JSON as received). New lines are taken from
 * the receiver on a timer; while paused they wait there.
 */
class TraceTailPanel(private val project: Project, private val toolWindow: ToolWindow) : Disposable {

    private val server = project.service<TraceTailServer>()
    private val model = TraceModel()
    private val details = console()
    private val flat = console()
    private val raw = console()
    private val tree = TraceTree(project, model, ::showDetails)
    private val statusLines = mutableListOf<JBLabel>()
    private var paused = false
    private var ticks = 0
    private val timer = Timer(TICK_MILLIS) { tick() }

    init {
        val shared = listOf(PauseAction(), ClearAction(), LevelGroup())
        val actions = CommonActionsManager.getInstance()
        val treeActions = shared + listOf(
            actions.createExpandAllAction(tree.expander, tree.component),
            actions.createCollapseAllAction(tree.expander, tree.component),
        )
        val splitter = OnePixelSplitter(false, 0.7f).apply {
            firstComponent = tree.component
            secondComponent = details.component
        }
        addTab("Tree", splitter, treeActions)
        addTab("Flat", flat.component, shared)
        addTab("Raw", raw.component, shared)
        timer.start()
    }

    override fun dispose() {
        timer.stop()
    }

    private fun console(): ConsoleView {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
        Disposer.register(this, console)
        return console
    }

    private fun addTab(title: String, component: JComponent, actions: List<AnAction>) {
        val toolbar = ActionManager.getInstance().createActionToolbar("TraceTail$title", DefaultActionGroup(actions), false)
        toolbar.targetComponent = component
        val status = JBLabel().apply {
            border = JBUI.Borders.empty(2, 8)
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
        statusLines += status
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

    private fun tick() {
        if (!paused) {
            val lines = server.drain(PULL_MAX).mapNotNull(LogLine::parse)
            if (lines.isNotEmpty()) apply(model.add(lines))
        }
        if (++ticks % REFRESH_EVERY_TICKS == 0) tree.refreshRunning()
        updateStatus()
    }

    private fun apply(change: Change) {
        tree.apply(change)
        if (change.reset) {
            flat.clear()
            raw.clear()
        }
        for (line in change.admitted) {
            flat.print(flatText(line), contentType(line.level))
            raw.print(line.json + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
        }
    }

    private fun showDetails(line: LogLine?) {
        details.clear()
        if (line == null) return
        details.print(line.prettyJson() + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
        line.stackTrace?.let { details.print("\n" + it + "\n", ConsoleViewContentType.ERROR_OUTPUT) }
    }

    private fun flatText(l: LogLine) = buildString {
        append(Palette.time(l.time)).append(' ').append(l.level.name.padEnd(5)).append(' ').append(l.app).append("  ").append(l.title)
        l.phase?.let { append(' ').append(it) }
        l.trace?.let { append(" trace=").append(it.take(8)) }
        l.user?.let { append(" user=").append(it) }
        for ((key, value) in l.fields) if (key != "startTime") append(' ').append(key).append('=').append(value)
        append('\n')
        l.stackTrace?.let { append(it).append('\n') }
    }

    private fun contentType(level: Level) = when (level) {
        Level.DEBUG -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
        Level.INFO -> ConsoleViewContentType.NORMAL_OUTPUT
        Level.WARN -> ConsoleViewContentType.LOG_WARNING_OUTPUT
        Level.ERROR -> ConsoleViewContentType.LOG_ERROR_OUTPUT
    }

    private fun updateStatus() {
        val s = server.stats()
        val text = buildString {
            append("Listening on port ${s.port}")
            append(" · ${Palette.plural(s.connections, "app")} connected")
            append(" · ${s.received} lines received")
            if (s.dropped > 0) append(" · ${s.dropped} oldest lines discarded")
            if (paused) append(" · paused")
        }
        statusLines.forEach { it.text = text }
    }

    /* ---------- actions ---------- */

    private inner class PauseAction : ToggleAction("Pause", "Stop taking new lines; they wait until resumed", AllIcons.Actions.Pause), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = paused
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            paused = state
            updateStatus()
        }
    }

    private inner class ClearAction : DumbAwareAction("Clear", "Remove the lines shown so far", AllIcons.Actions.GC) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            apply(model.clear())
            showDetails(null)
        }
    }

    /** One submenu for all apps and one for each app, each offering the four levels. */
    private inner class LevelGroup : ActionGroup("Level", "Hide the lines below a level", AllIcons.General.Filter), DumbAware {
        init {
            templatePresentation.isPopupGroup = true
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun getChildren(e: AnActionEvent?): Array<AnAction> =
            (listOf<String?>(null) + model.apps).map { app ->
                DefaultActionGroup(app ?: "All apps", true).apply { Level.entries.forEach { add(LevelAction(app, it)) } }
            }.toTypedArray()
    }

    private inner class LevelAction(private val app: String?, private val level: Level) : ToggleAction(level.name), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = model.isLevel(app, level)
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!state) return
            model.setLevel(app, level)
            apply(model.rebuild())
        }
    }

    private companion object {
        const val TICK_MILLIS = 250
        const val REFRESH_EVERY_TICKS = 2
        const val PULL_MAX = 2_000
    }
}
