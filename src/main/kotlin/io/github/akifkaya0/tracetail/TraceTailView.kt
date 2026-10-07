package io.github.akifkaya0.tracetail

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import javax.swing.JComponent

/**
 * The log view: a single HTML page in the IDE's embedded browser. The page pulls the received lines
 * itself, so it takes them at its own pace and stops taking them while paused.
 */
class TraceTailView(server: TraceTailServer, parent: Disposable) {

    private val browser = JBCefBrowser()

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, browser)
        val pull = JBCefJSQuery.create(browser as JBCefBrowserBase)
        Disposer.register(browser, pull)
        pull.addHandler { request ->
            val max = request.toIntOrNull()?.coerceIn(1, PULL_MAX) ?: PULL_MAX
            JBCefJSQuery.Response(jsonArray(server.drain(max)))
        }
        browser.loadHTML(page().replace(BRIDGE_MARKER, pull.inject("max", "onLines", "onFail")))
    }

    private fun page(): String =
        javaClass.getResource("/web/index.html")?.readText()
            ?: error("web/index.html is missing from the plugin")

    private companion object {
        const val BRIDGE_MARKER = "/*TRACE_TAIL_BRIDGE*/"
        const val PULL_MAX = 5_000

        /** The lines as a JSON array of strings; the page parses each line on its own. */
        fun jsonArray(lines: List<String>): String = lines.joinToString(",", "[", "]", transform = ::jsonString)

        fun jsonString(s: String): String = buildString(s.length + 2) {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' || c == ' ' || c == ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
