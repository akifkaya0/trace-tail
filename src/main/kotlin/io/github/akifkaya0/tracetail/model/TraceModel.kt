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
 * and the new ones, so changing it rebuilds everything from the kept lines. A request's main step
 * keeps its START and END lines whatever their level, so a request that shows has its main row.
 * It keeps the last [TRACE_MAX] requests, with at most [TRACE_LINES_MAX] lines in all.
 */
class TraceModel {

    private val appIndex = LinkedHashMap<String, Int>()
    private val appLevel = HashMap<String, Level>()
    private var allLevel = Level.DEBUG
    private val received = ArrayDeque<LogLine>()
    private val shown = ArrayDeque<LogLine>()

    /** A step whose START line is hidden still exists in the app. The lines inside it hang under the nearest shown ancestor. */
    private val skipped = LinkedHashMap<String, String?>()

    /** The main steps' lines that the level hides, kept until a line of their request shows. */
    private val held = LinkedHashMap<String, MutableList<LogLine>>()

    val traces = LinkedHashMap<String, Trace>()

    /** The lines of [traces], all together. */
    private var traceLines = 0

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
            take(line, changed, removed)?.let { admitted += it }
        }
        return Change(admitted, changed, removed, reset = false)
    }

    fun rebuild(): Change {
        traces.clear()
        traceLines = 0
        skipped.clear()
        held.clear()
        shown.clear()
        for (line in received) take(line, LinkedHashSet(), ArrayList())
        return snapshot()
    }

    fun clear(): Change {
        received.clear()
        traces.clear()
        traceLines = 0
        skipped.clear()
        held.clear()
        shown.clear()
        return snapshot()
    }

    /** Everything shown now, for a view that starts late. */
    fun snapshot() = Change(shown.toList(), traces.values.toList(), emptyList(), reset = true)

    private fun show(line: LogLine) {
        shown.addLast(line)
        if (shown.size > KEEP_MAX) shown.removeFirst()
    }

    private fun levelOf(app: String) = appLevel[app] ?: allLevel

    /** Shows a line that passes the level, as [admit] places it, and holds a hidden main step's line; returns the shown line. */
    private fun take(line: LogLine, changed: MutableSet<Trace>, removed: MutableList<Trace>): LogLine? {
        val shownLine = admit(line)
        if (shownLine != null) {
            show(shownLine)
            place(shownLine, changed, removed)
        } else if (isMain(line)) {
            hold(line, changed)
        }
        return shownLine
    }

    private fun admit(line: LogLine): LogLine? {
        if (line.level < levelOf(line.app)) {
            // a hidden main step is not skipped: its lines are held, and the lines inside it stay under it
            if (line.phase == Phase.START && line.span != null && !isMain(line)) {
                skipped[line.span] = resolve(line.parent)
                if (skipped.size > SKIPPED_MAX) skipped.remove(skipped.keys.first())
            }
            return null
        }
        val span = if (line.phase != null) line.span else resolve(line.span) ?: line.span
        val parent = if (line.phase != null) resolve(line.parent) else null
        return if (span == line.span && parent == line.parent) line else line.placed(span, parent)
    }

    /** A line of a main step: one that no other step started. */
    private fun isMain(line: LogLine) = line.phase != null && line.parent == null && line.trace != null

    /** Keeps a hidden main step's line with its request, or until the request shows. */
    private fun hold(line: LogLine, changed: MutableSet<Trace>) {
        val id = line.trace ?: return
        val trace = traces[id]
        if (trace != null) {
            addTo(trace, line)
            changed += trace
            return
        }
        held.getOrPut(id) { ArrayList() } += line
        if (held.size > HELD_MAX) held.remove(held.keys.first())
    }

    private fun resolve(span: String?): String? {
        var s = span
        while (s != null && skipped.containsKey(s)) s = skipped[s]
        return s
    }

    private fun place(line: LogLine, changed: MutableSet<Trace>, removed: MutableList<Trace>) {
        val id = line.trace ?: return   // a line outside any request shows only in the flat and raw tabs
        val trace = traces.getOrPut(id) { Trace(id).also { t -> held.remove(id)?.forEach { addTo(t, it) } } }
        addTo(trace, line)
        changed += trace
        // the oldest requests go, but never the one the line went to
        while (traces.size > TRACE_MAX || traceLines > TRACE_LINES_MAX) {
            val oldest = traces.values.firstOrNull { it !== trace } ?: break
            traces.remove(oldest.id)
            traceLines -= oldest.lines.size
            changed -= oldest
            removed += oldest
        }
    }

    private fun addTo(trace: Trace, line: LogLine) {
        val before = trace.lines.size
        trace.add(line)
        traceLines += trace.lines.size - before
    }

    companion object {
        const val KEEP_MAX = 10_000
        const val TRACE_MAX = 300
        const val TRACE_LINES_MAX = 100_000
        private const val SKIPPED_MAX = 20_000
        private const val HELD_MAX = 2_000
    }
}
