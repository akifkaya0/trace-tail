package io.github.akifkaya0.tracetail

import com.intellij.execution.impl.ConsoleBuffer
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.akifkaya0.tracetail.model.Change
import io.github.akifkaya0.tracetail.model.Level
import io.github.akifkaya0.tracetail.model.TraceModel
import io.github.akifkaya0.tracetail.ui.FlatConsole
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

        fun softWrapsChanged(on: Boolean) {}
    }

    private val server = project.service<TraceTailServer>()
    private val listeners = mutableListOf<Listener>()
    private val timer = Timer(TICK_MILLIS) { tick() }

    val model = TraceModel(measure = { FlatConsole.length(it) }, limit = { runLimit() })

    /** While paused, new lines wait in the receiver. */
    var paused = false

    /** Whether long lines wrap in every view; the IDE remembers it. */
    var softWraps: Boolean = PropertiesComponent.getInstance().getBoolean(SOFT_WRAPS_KEY, false)
        set(value) {
            field = value
            PropertiesComponent.getInstance().setValue(SOFT_WRAPS_KEY, value, false)
            listeners.toList().forEach { it.softWrapsChanged(value) }
        }

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

    /** Deletes the lines of the run with that process id; see [TraceModel.removeRun]. */
    fun removeRun(pid: Long, ends: Boolean) = publish(model.removeRun(pid, ends))

    /** A run started; its process id may be one that Windows gave out again. */
    fun started(pid: Long) = model.accept(pid)

    /** How many lines the next tick takes: as many as the views take in about [TICK_BUDGET_MILLIS], as the last tick went. */
    private var pull = PULL_MAX

    private fun tick() {
        if (!paused) {
            val started = System.nanoTime()
            val drained = server.drain(pull)
            if (drained.isNotEmpty()) publish(model.add(drained))
            // the rest waits in the receiver, so the IDE stays responsive however fast the apps log
            val millis = maxOf((System.nanoTime() - started) / 1_000_000, 1)
            if (drained.size == pull || millis > TICK_BUDGET_MILLIS) {
                pull = (drained.size * TICK_BUDGET_MILLIS / millis).toInt().coerceIn(PULL_MIN, PULL_MAX)
            }
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
        private const val PULL_MIN = 100
        private const val TICK_BUDGET_MILLIS = 25L
        private const val SOFT_WRAPS_KEY = "tracetail.softWraps"
        private const val NO_LIMIT_KB = 20_000L

        /**
         * The Flat text a run keeps: the console's, as set in Settings | Editor | General | Console,
         * read each time, or [NO_LIMIT_KB] when the console keeps everything.
         */
        fun runLimit(): Long =
            if (ConsoleBuffer.useCycleBuffer() && ConsoleBuffer.getCycleBufferSize() > 0) ConsoleBuffer.getCycleBufferSize().toLong() else NO_LIMIT_KB * 1024
    }
}
