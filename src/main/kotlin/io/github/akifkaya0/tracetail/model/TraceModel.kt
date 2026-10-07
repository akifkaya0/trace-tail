package io.github.akifkaya0.tracetail.model

/** What one update changed, for the views to follow. */
class Change(
    /** The lines that passed the level filter, in arrival order. */
    val admitted: List<LogLine>,
    /** The requests that got new lines. */
    val changed: Collection<Trace>,
    /** The oldest requests, dropped to keep the view small. */
    val removed: Collection<Trace>,
    /** True when the views start over from [TraceModel.traces] and [admitted]. */
    val reset: Boolean,
)

/**
 * The received lines and the requests built from them. The level filter applies to the kept lines
 * and the new ones, so changing it rebuilds everything from the kept lines.
 */
class TraceModel {

    private val appIndex = LinkedHashMap<String, Int>()
    private val appLevel = HashMap<String, Level>()
    private var allLevel = Level.DEBUG
    private val received = ArrayDeque<LogLine>()

    /** A step whose START line is hidden still exists in the app. The lines inside it hang under the nearest shown ancestor. */
    private val skipped = LinkedHashMap<String, String?>()

    val traces = LinkedHashMap<String, Trace>()

    /** The apps in the order their first line arrived. */
    val apps: Set<String> get() = appIndex.keys

    fun appIndex(app: String): Int = appIndex[app] ?: 0

    /** With [app] null: whether every app shows from [level] on. */
    fun isLevel(app: String?, level: Level): Boolean =
        if (app == null) allLevel == level && appLevel.values.all { it == level } else levelOf(app) == level

    /** With [app] null: sets the level of every app, and of the apps still to come. */
    fun setLevel(app: String?, level: Level) {
        if (app == null) {
            allLevel = level
            appLevel.replaceAll { _, _ -> level }
        } else {
            appLevel[app] = level
        }
    }

    fun add(lines: List<LogLine>): Change {
        val admitted = ArrayList<LogLine>()
        val changed = LinkedHashSet<Trace>()
        val removed = ArrayList<Trace>()
        for (line in lines) {
            if (line.app !in appIndex) {
                appIndex[line.app] = appIndex.size
                appLevel[line.app] = allLevel
            }
            received.addLast(line)
            if (received.size > KEEP_MAX) received.removeFirst()
            admit(line)?.let { admitted += it; place(it, changed, removed) }
        }
        return Change(admitted, changed, removed, reset = false)
    }

    fun rebuild(): Change {
        traces.clear()
        skipped.clear()
        val admitted = ArrayList<LogLine>()
        for (line in received) admit(line)?.let { admitted += it; place(it, LinkedHashSet(), ArrayList()) }
        return Change(admitted, traces.values.toList(), emptyList(), reset = true)
    }

    fun clear(): Change {
        received.clear()
        traces.clear()
        skipped.clear()
        return Change(emptyList(), emptyList(), emptyList(), reset = true)
    }

    private fun levelOf(app: String) = appLevel[app] ?: allLevel

    private fun admit(line: LogLine): LogLine? {
        if (line.level < levelOf(line.app)) {
            if (line.phase == Phase.START && line.span != null) {
                skipped[line.span] = resolve(line.parent)
                if (skipped.size > SKIPPED_MAX) skipped.remove(skipped.keys.first())
            }
            return null
        }
        val span = if (line.phase != null) line.span else resolve(line.span) ?: line.span
        val parent = if (line.phase != null) resolve(line.parent) else null
        return if (span == line.span && parent == line.parent) line else line.placed(span, parent)
    }

    private fun resolve(span: String?): String? {
        var s = span
        while (s != null && skipped.containsKey(s)) s = skipped[s]
        return s
    }

    private fun place(line: LogLine, changed: MutableSet<Trace>, removed: MutableList<Trace>) {
        val id = line.trace ?: return   // a line outside any request shows only in the flat and raw tabs
        val trace = traces.getOrPut(id) { Trace(id) }
        trace.add(line)
        changed += trace
        if (traces.size > TRACE_MAX) {
            val oldest = traces.values.first()
            traces.remove(oldest.id)
            changed -= oldest
            removed += oldest
        }
    }

    companion object {
        const val KEEP_MAX = 10_000
        const val TRACE_MAX = 300
        private const val SKIPPED_MAX = 20_000
    }
}
