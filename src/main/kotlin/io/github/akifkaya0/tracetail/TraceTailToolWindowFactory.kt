package io.github.akifkaya0.tracetail

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import io.github.akifkaya0.tracetail.ui.TraceTailPanel

class TraceTailToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        Disposer.register(toolWindow.disposable, TraceTailPanel(project, toolWindow))
    }
}
