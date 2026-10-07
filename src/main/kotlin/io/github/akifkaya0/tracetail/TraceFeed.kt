package io.github.akifkaya0.tracetail

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.Level
import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.TraceModel
import javax.swing.Timer

/**
 * Takes the received lines into the project's [TraceModel] on a timer, whether or not a view is
 * open, and tells the views what changed. Everything here runs on the UI thread.
 */
@Service(Service.Level.PROJECT)
class TraceFeed(project: Project) : Disposable {

    interface Listener {
        fun changed(change: Change) {}

        /** Called on every tick, for views that redraw running steps. */
        fun ticked() {}
    }

    private val server = project.service<TraceTailServer>()
    private val listeners = mutableListOf<Listener>()
    private val timer = Timer(TICK_MILLIS) { tick() }

    val model = TraceModel()

    /** While paused, new lines wait in the receiver. */
    var paused = false

    init {
        timer.start()
    }

    /** Starts [listener] with everything shown now; it stops with [parent]. */
    fun subscribe(parent: Disposable, listener: Listener) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
        listener.changed(model.snapshot())
    }

    fun setLevel(app: String?, level: Level) {
        model.setLevel(app, level)
        publish(model.rebuild())
    }

    fun clear() = publish(model.clear())

    private fun tick() {
        if (!paused) {
            val lines = server.drain(PULL_MAX).mapNotNull(LogLine::parse)
            if (lines.isNotEmpty()) publish(model.add(lines))
        }
        listeners.toList().forEach { it.ticked() }
    }

    private fun publish(change: Change) = listeners.toList().forEach { it.changed(change) }

    override fun dispose() {
        timer.stop()
    }

    companion object {
        const val TICK_MILLIS = 250
        private const val PULL_MAX = 2_000
    }
}
