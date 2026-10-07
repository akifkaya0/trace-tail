package io.github.akifkaya0.tracetail

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import javax.swing.JComponent
import javax.swing.SwingConstants

/** Shows the log view, a single HTML page, in the IDE's embedded browser. */
class TraceTailToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val component: JComponent = if (JBCefApp.isSupported()) {
            val browser = JBCefBrowser()
            Disposer.register(toolWindow.disposable, browser)
            browser.loadHTML(page())
            browser.component
        } else {
            JBLabel("Trace Tail needs the embedded browser (JCEF), which this IDE does not provide.", SwingConstants.CENTER)
        }
        val content = ContentFactory.getInstance().createContent(component, null, false)
        toolWindow.contentManager.addContent(content)
    }

    private fun page(): String =
        javaClass.getResource("/web/index.html")?.readText()
            ?: error("web/index.html is missing from the plugin")
}
