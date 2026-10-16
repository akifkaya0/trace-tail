package io.github.akifkaya0.tracetail

import com.intellij.execution.ExecutionListener
import com.intellij.execution.Executor
import com.intellij.execution.actions.ClearConsoleAction
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.RunContentWithExecutorListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.ui.SoftWrapAction
import io.github.akifkaya0.tracetail.ui.TraceView
import javax.swing.SwingUtilities

/** Tells [TraceTailRunTabs] about every run and debug session that starts. */
class TraceTailRunListener(private val project: Project) : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (env.runProfile !is RunConfiguration) return
        ApplicationManager.getApplication().invokeLater({ project.service<TraceTailRunTabs>().started(handler) }, project.disposed)
    }
}

/**
 * Follows the runs, each a process by its id, which the agent sends with every line.
 *
 * - Adds a Trace Tail tab to a run's Run or Debug window once its first line arrives, so runs that
 *   do not send their logs get no empty tab. The tab shows the requests the run took part in, with
 *   the lines the other runs wrote for them.
 * - Deletes a run's lines when its tab goes: closed, or reused by Rerun. The run's later lines are
 *   dropped too, such as those that waited while the view was paused.
 * - Deletes a run's lines when Clear All clears its console; the lines it sends next still come.
 */
@Service(Service.Level.PROJECT)
class TraceTailRunTabs(private val project: Project) : Disposable {

    private class Run(val pid: Long, val handler: ProcessHandler)

    private val waiting = mutableListOf<Run>()
    private val feed = project.service<TraceFeed>()

    init {
        feed.subscribe(this, object : TraceFeed.Listener {
            override fun changed(change: Change) {
                if (waiting.isNotEmpty() && change.admitted.isNotEmpty()) showTabs()
            }
        })
        project.messageBus.connect(this).subscribe(RunContentManager.TOPIC, object : RunContentWithExecutorListener {
            override fun contentSelected(descriptor: RunContentDescriptor?, executor: Executor) = Unit
            override fun contentRemoved(descriptor: RunContentDescriptor?, executor: Executor) {
                descriptor?.processHandler?.pid()?.let { feed.removeRun(it, ends = true) }
            }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(AnActionListener.TOPIC, object : AnActionListener {
            override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                if (action !is ClearConsoleAction) return
                val console = event.getData(LangDataKeys.CONSOLE_VIEW) ?: return
                val descriptor = event.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR) ?: return
                // only the run's own console: the Trace Tail tab's consoles are in the same window
                val own = descriptor.executionConsole?.component ?: return
                if (SwingUtilities.isDescendingFrom(console.component, own)) descriptor.processHandler?.pid()?.let { feed.removeRun(it, ends = false) }
            }
        })
    }

    fun started(handler: ProcessHandler) {
        val pid = handler.pid() ?: return
        feed.started(pid)
        waiting += Run(pid, handler)
        showTabs()
    }

    private fun showTabs() {
        val descriptors = RunContentManager.getInstance(project).allDescriptors
        val iterator = waiting.iterator()
        while (iterator.hasNext()) {
            val run = iterator.next()
            if (!feed.model.hasRun(run.pid)) {
                if (run.handler.isProcessTerminated) iterator.remove()
                continue
            }
            val descriptor = descriptors.firstOrNull { it.processHandler === run.handler } ?: continue
            iterator.remove()
            // A run window without tabs, such as a plain Run console, has no layout to add to.
            val layout = descriptor.runnerLayoutUi ?: continue
            val view = TraceView(project, feed, { run.pid in it.pids })
            Disposer.register(descriptor, view)
            val content = layout.createContent(CONTENT_ID, view.component, "Trace Tail", ICON, null)
            content.isCloseable = false
            content.setActions(DefaultActionGroup(view.treeActions + SoftWrapAction(feed)), "TraceTailRunTab", view.component)
            layout.addContent(content)
        }
    }

    override fun dispose() = Unit

    private companion object {
        const val CONTENT_ID = "TraceTail"
        val ICON = IconLoader.getIcon("/icons/traceTail.svg", TraceTailRunTabs::class.java)

        /** The process id of a run started on this computer; the agent sends the same. */
        fun ProcessHandler.pid(): Long? = (this as? BaseProcessHandler<*>)?.process?.let { runCatching { it.pid() }.getOrNull() }
    }
}
