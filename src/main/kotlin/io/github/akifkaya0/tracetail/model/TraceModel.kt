package io.github.akifkaya0.tracetail.model

/** What one update changed, for the views to follow. */
class Change(
    /** The lines that passed the level filter, in arrival order. */
    val admitted: List<LogLine>,
    /** Shown lines that were deleted; the Flat and Raw tabs take them out. */
    val deleted: Collection<LogLine>,
    /** The requests that got new lines or lost some. */
    val changed: Collection<Trace>,
    /** The requests that lost all their lines. */
    val removed: Collection<Trace>,
    /** True when the views start over from [TraceModel.traces] and [admitted]. */
    val reset: Boolean,
)

/**
 * The received lines and the requests built from them. The level filter applies to the kept lines
 * and the new ones, so changing it rebuilds everything from the kept lines. A request's main step
 * keeps its START and END lines whatever their level, so a request that shows has its main row.
 *
 * Each run, a process by its id, keeps lines up to [limit] of the Flat tab's text, the lines the
 * level hides included. Past that, its oldest line goes, with its whole request from every run;
 * a request that got a line in the last [ACTIVE_MS] loses only its oldest lines. All runs together
 * keep at most [LINES_MAX] lines; past that, the run with the most lines loses its oldest request
 * the same way.
 */
class TraceModel(
    /** The text a line takes in the Flat tab. */
    private val measure: (LogLine) -> Int = { it.json.length },
    /** The text each run may keep; asked on every update, so a changed setting applies at once. */
    private val limit: () -> Long = { Long.MAX_VALUE },
) {

    private class Run(val app: String) {
        /** Oldest first; deleted lines leave at the end of the update that deleted them. */
        val lines = ArrayDeque<LogLine>()
        var size = 0L
        var count = 0
    }

    /** A request's kept lines from every run, whatever their level, and when its last line came. */
    private class Kept {
        val lines = ArrayList<LogLine>()
        var last = 0L
    }

    /** What one update collects, for its [Change]. */
    private inner class Update {
        val admitted = ArrayList<LogLine>()
        val deleted = ArrayList<LogLine>()
        val changed = LinkedHashSet<Trace>()
        val removed = ArrayList<Trace>()
        val gone = HashSet<Long>()
        val touched = HashSet<String>()

        fun finish(): Change {
            if (gone.isNotEmpty()) {
                for (run in runs.values) {
                    // the oldest lines go most often; a run's other deleted lines, such as a whole request's, need a pass
                    while (run.lines.firstOrNull()?.let { it.seq in gone } == true) run.lines.removeFirst()
                    if (run.lines.size > run.count) run.lines.removeIf { it.seq in gone }
                }
                runs.values.removeIf { it.count == 0 }
                for (id in touched) {
                    requests[id]?.let { kept ->
                        if (kept.lines.removeIf { it.seq in gone } && kept.lines.isEmpty()) {
                            requests.remove(id)
                            headless.remove(id)
                        }
                    }
                    held[id]?.removeIf { it.seq in gone }
                    val trace = traces[id] ?: continue
                    traceLines -= trace.lines.size
                    trace.remove(gone)
                    traceLines += trace.lines.size
                    if (id in headless) trace.partial = true
                    if (trace.lines.isEmpty()) {
                        traces.remove(id)
                        changed -= trace
                        removed += trace
                    } else {
                        changed += trace
                    }
                }
                // a line both added and deleted now never reached the views
                val added = admitted.mapTo(HashSet()) { it.seq }
                deleted.removeIf { it.seq in added }
                admitted.removeIf { it.seq in gone }
            }
            return Change(admitted, deleted, changed, removed, reset = false)
        }
    }

    private val appIndex = LinkedHashMap<String, Int>()
    private val appLevel = HashMap<String, Level>()
    private var allLevel = Level.DEBUG
    private val runs = LinkedHashMap<Long?, Run>()
    private val requests = HashMap<String, Kept>()
    private val shown = LinkedHashMap<Long, LogLine>()

    /** Runs that ended, by their process id; lines they still send, or that waited while paused, are dropped. */
    private val ended = HashSet<Long>()

    /** The requests whose oldest lines a run's limit deleted. */
    private val headless = HashSet<String>()

    /** A step whose START line is hidden still exists in the app. The lines inside it hang under the nearest shown ancestor. */
    private val skipped = LinkedHashMap<String, String?>()

    /** The main steps' lines that the level hides, kept until a line of their request shows. */
    private val held = LinkedHashMap<String, MutableList<LogLine>>()

    val traces = LinkedHashMap<String, Trace>()

    /** The lines of [traces], all together. */
    var traceLines = 0
        private set

    /** The lines kept from every run. */
    var kept = 0
        private set

    /** The app whose oldest lines go since [LINES_MAX] was reached; null below it. */
    var capped: String? = null
        private set

    /** The apps in the order their first line arrived. */
    val apps: Set<String> get() = appIndex.keys

    fun appIndex(app: String): Int = appIndex[app] ?: 0

    /** Whether the run has kept lines. */
    fun hasRun(pid: Long): Boolean = runs.containsKey(pid)

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

    fun add(lines: List<LogLine>, now: Long = System.currentTimeMillis()): Change {
        val update = Update()
        val limit = limit()
        for (line in lines) {
            val pid = line.pid
            if (pid != null && pid in ended) continue
            if (line.app !in appIndex) {
                appIndex[line.app] = appIndex.size
                appLevel[line.app] = allLevel
            }
            line.size = measure(line)
            val run = runs.getOrPut(pid) { Run(line.app) }
            run.lines.addLast(line)
            run.size += line.size
            run.count++
            kept++
            line.trace?.let { id -> requests.getOrPut(id) { Kept() }.also { it.lines += line; it.last = now } }
            take(line, update.changed)?.let { update.admitted += it }
            while (run.size > limit && trim(run, now, update)) {}
            while (kept > LINES_MAX) {
                val biggest = runs.values.maxBy { it.count }
                capped = biggest.app
                trim(biggest, now, update)
            }
        }
        return update.finish()
    }

    /**
     * Deletes a run's lines; the other runs' lines of its requests stay. Once a run [ends], the
     * lines it still sends are dropped too, until [accept] takes its process id back.
     */
    fun removeRun(pid: Long, ends: Boolean): Change {
        if (ends) ended += pid
        val update = Update()
        runs[pid]?.lines?.toList()?.forEach { delete(it, update) }
        capped = null
        return update.finish()
    }

    /** A new process got the id of one that ended, as Windows may do. */
    fun accept(pid: Long) {
        ended -= pid
    }

    fun rebuild(): Change {
        val lines = runs.values.flatMap { it.lines }.sortedBy { it.seq }
        clearView()
        val changed = LinkedHashSet<Trace>()
        for (line in lines) take(line, changed)
        return snapshot()
    }

    fun clear(): Change {
        runs.clear()
        requests.clear()
        headless.clear()
        kept = 0
        capped = null
        clearView()
        return snapshot()
    }

    /** Everything shown now, for a view that starts late. */
    fun snapshot() = Change(shown.values.toList(), emptyList(), traces.values.toList(), emptyList(), reset = true)

    private fun clearView() {
        traces.clear()
        traceLines = 0
        skipped.clear()
        held.clear()
        shown.clear()
    }

    /** Deletes the run's oldest line, or its whole request when that got no line for [ACTIVE_MS]; false when the run has none. */
    private fun trim(run: Run, now: Long, update: Update): Boolean {
        while (run.lines.firstOrNull()?.seq?.let { it in update.gone } == true) run.lines.removeFirst()
        val oldest = run.lines.firstOrNull() ?: return false
        val id = oldest.trace
        val request = id?.let(requests::get)
        if (request == null || now - request.last < ACTIVE_MS) {
            if (id != null) headless += id
            delete(oldest, update)
        } else {
            requests.remove(id)
            headless.remove(id)
            held.remove(id)
            request.lines.forEach { delete(it, update) }
            traces.remove(id)?.let { trace ->
                traceLines -= trace.lines.size
                update.changed -= trace
                update.removed += trace
            }
        }
        return true
    }

    private fun delete(line: LogLine, update: Update) {
        if (!update.gone.add(line.seq)) return
        runs[line.pid]?.let {
            it.size -= line.size
            it.count--
        }
        kept--
        shown.remove(line.seq)?.let { update.deleted += it }
        line.trace?.let { update.touched += it }
    }

    private fun levelOf(app: String) = appLevel[app] ?: allLevel

    /** Shows a line that passes the level, as [admit] places it, and holds a hidden main step's line; returns the shown line. */
    private fun take(line: LogLine, changed: MutableSet<Trace>): LogLine? {
        val shownLine = admit(line)
        if (shownLine != null) {
            shown[shownLine.seq] = shownLine
            place(shownLine, changed)
        } else if (isMain(line)) {
            hold(line, changed)
        }
        return shownLine
    }

    private fun admit(line: LogLine): LogLine? {
        if (line.level < levelOf(line.app)) {
            // a hidden main step is not skipped: its lines are held, and the lines inside it stay under it
            if (line.phase == Phase.START && line.span != null && !isMain(line)) {
                val ancestor = resolve(line.parent)
                // a step that leads back to itself, by its own id or through another request's, would make resolve loop forever
                if (ancestor != line.span) {
                    skipped[line.span] = ancestor
                    if (skipped.size > SKIPPED_MAX) skipped.remove(skipped.keys.first())
                }
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

    private fun place(line: LogLine, changed: MutableSet<Trace>) {
        val id = line.trace ?: return   // a line outside any request shows only in the flat and raw tabs
        val trace = traces.getOrPut(id) {
            Trace(id).also { t ->
                t.partial = id in headless
                held.remove(id)?.forEach { addTo(t, it) }
            }
        }
        addTo(trace, line)
        changed += trace
    }

    private fun addTo(trace: Trace, line: LogLine) {
        val before = trace.lines.size
        trace.add(line)
        traceLines += trace.lines.size - before
    }

    companion object {
        const val LINES_MAX = 50_000
        const val ACTIVE_MS = 30_000L
        private const val SKIPPED_MAX = 20_000
        private const val HELD_MAX = 2_000
    }
}
