package io.github.akifkaya0.tracetail.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
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
import com.intellij.openapi.editor.Editor
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
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The tool window's tabs: Tree (every request, from every app), Flat (every line in arrival order)
 * and Raw (the JSON as received).
 */
class TraceTailPanel(private val project: Project, private val toolWindow: ToolWindow) : Disposable {

    private val server = project.service<TraceTailServer>()
    private val feed = project.service<TraceFeed>()
    private val flat = FlatConsole(project, this, feed.model)
    private val raw = console()
    private val statusLines = mutableListOf<JBLabel>()

    init {
        val view = TraceView(project, feed) { true }
        Disposer.register(this, view)
        val shared = listOf(PauseAction(), ClearAction(), LevelGroup(), SoftWrapAction(feed))
        addTab("Tree", view.component, shared + view.treeActions)
        addTab("Flat", flat.component, shared + scrollToEnd(flat.editor))
        addTab("Raw", raw.component, shared + scrollToEnd((raw as? ConsoleViewImpl)?.editor))
        softWraps(feed.softWraps)
        feed.subscribe(this, object : TraceFeed.Listener {
            override fun changed(change: Change) = print(change)
            override fun ticked() = updateStatus()
            override fun softWrapsChanged(on: Boolean) = softWraps(on)
        })
    }

    override fun dispose() = Unit

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

    /** The console's own Scroll to the End; a console scrolled to its end keeps following new lines. */
    private fun scrollToEnd(editor: Editor?): List<AnAction> = listOfNotNull(editor?.let(::ScrollToTheEndToolbarAction))

    private fun softWraps(on: Boolean) {
        flat.setSoftWraps(on)
        raw.setSoftWraps(on)
    }

    private fun print(change: Change) {
        flat.print(change)
        if (change.reset) raw.clear()
        for (line in change.admitted) raw.print(line.json + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
    }

    private fun updateStatus() {
        val s = server.stats()
        val text = buildString {
            append("Listening on port ${s.port}")
            append(" · ${Palette.plural(s.connections, "app")} connected")
            append(" · ${s.received} lines received")
            if (s.dropped > 0) append(" · ${s.dropped} oldest lines discarded")
            if (feed.paused) append(" · paused")
        }
        statusLines.forEach { it.text = text }
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
