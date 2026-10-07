package io.github.akifkaya0.tracetail

import com.intellij.execution.ExecutionListener
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.ui.SoftWrapAction
import io.github.akifkaya0.tracetail.ui.TraceView

/** Tells [TraceTailRunTabs] about every run and debug session that starts. */
class TraceTailRunListener(private val project: Project) : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val configuration = env.runProfile as? RunConfiguration ?: return
        ApplicationManager.getApplication().invokeLater({ project.service<TraceTailRunTabs>().started(configuration.name, handler) }, project.disposed)
    }
}

/**
 * Adds a Trace Tail tab to the Run or Debug window of an app once its first line arrives, so apps
 * that do not send their logs get no empty tab. The tab shows the requests the app took part in,
 * with the lines the other apps wrote for them. The run's app is the run configuration's name,
 * which the plugin also passes as `tracetail.app`.
 */
@Service(Service.Level.PROJECT)
class TraceTailRunTabs(private val project: Project) : Disposable {

    private class Run(val app: String, val handler: ProcessHandler)

    private val waiting = mutableListOf<Run>()
    private val feed = project.service<TraceFeed>()

    init {
        feed.subscribe(this, object : TraceFeed.Listener {
            override fun changed(change: Change) {
                if (waiting.isNotEmpty() && change.admitted.isNotEmpty()) showTabs()
            }
        })
    }

    fun started(app: String, handler: ProcessHandler) {
        waiting += Run(app, handler)
        showTabs()
    }

    private fun showTabs() {
        val descriptors = RunContentManager.getInstance(project).allDescriptors
        val iterator = waiting.iterator()
        while (iterator.hasNext()) {
            val run = iterator.next()
            if (run.app !in feed.model.apps) {
                if (run.handler.isProcessTerminated) iterator.remove()
                continue
            }
            val descriptor = descriptors.firstOrNull { it.processHandler === run.handler } ?: continue
            iterator.remove()
            // A run window without tabs, such as a plain Run console, has no layout to add to.
            val layout = descriptor.runnerLayoutUi ?: continue
            val view = TraceView(project, feed) { run.app in it.apps }
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
    }
}
