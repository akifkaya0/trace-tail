package io.github.akifkaya0.tracetail

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer

/** Shows the log view, a single HTML page, in the IDE's embedded browser, with a status line below it. */
class TraceTailToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())
        panel.add(view(toolWindow), BorderLayout.CENTER)
        panel.add(statusLine(project, toolWindow), BorderLayout.SOUTH)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    private fun view(toolWindow: ToolWindow): JComponent {
        if (!JBCefApp.isSupported()) {
            return JBLabel("Trace Tail needs the embedded browser (JCEF), which this IDE does not provide.", SwingConstants.CENTER)
        }
        val browser = JBCefBrowser()
        Disposer.register(toolWindow.disposable, browser)
        browser.loadHTML(page())
        return browser.component
    }

    private fun page(): String =
        javaClass.getResource("/web/index.html")?.readText()
            ?: error("web/index.html is missing from the plugin")

    private fun statusLine(project: Project, toolWindow: ToolWindow): JComponent {
        val server = project.service<TraceTailServer>()
        val label = JBLabel()
        label.border = JBUI.Borders.empty(2, 8)
        label.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        val timer = Timer(STATUS_REFRESH_MILLIS) { label.text = describe(server.stats()) }
        timer.initialDelay = 0
        timer.start()
        Disposer.register(toolWindow.disposable) { timer.stop() }
        return label
    }

    private fun describe(stats: TraceTailServer.Stats): String = buildString {
        append("Listening on port ${stats.port}")
        append(" · ${stats.connections} ${if (stats.connections == 1) "app" else "apps"} connected")
        append(" · ${stats.received} lines received")
        if (stats.dropped > 0) append(" · ${stats.dropped} oldest lines discarded")
    }

    private companion object {
        const val STATUS_REFRESH_MILLIS = 500
    }
}
