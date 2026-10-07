package io.github.akifkaya0.tracetail.ui

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import io.github.akifkaya0.tracetail.TraceFeed

/** Wraps long lines, or keeps each on one line, in the tree and the consoles of every view. */
internal class SoftWrapAction(private val feed: TraceFeed) :
    ToggleAction("Soft-Wrap", "Wrap long lines at the window's edge", AllIcons.Actions.ToggleSoftWrap), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent) = feed.softWraps
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        feed.softWraps = state
    }
}

/** The console's own soft-wrap setting; needs the console's component to have been created. */
internal fun ConsoleView.setSoftWraps(on: Boolean) {
    (this as? ConsoleViewImpl)?.editor?.settings?.isUseSoftWraps = on
}
