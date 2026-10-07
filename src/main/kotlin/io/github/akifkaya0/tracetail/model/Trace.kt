package io.github.akifkaya0.tracetail.model

import java.util.Collections
import java.util.IdentityHashMap

/** One step of a request: the lines between a START and its END, under one span id. */
class Step(val id: String?) {
    var parent: String? = null
    var start = 0L
    var end: Long? = null
    var startLine: LogLine? = null
    var endLine: LogLine? = null
    var app: String? = null
    var event: String? = null
    val notes = mutableListOf<LogLine>()

    /** Set by [Trace.analyse]: no END came and none will. */
    var unfinished = false
        internal set
}

/**
 * The lines of one request, grouped into steps by trace.id, span.id and parent.id. A request keeps
 * at most [LINES_MAX] lines; past that, it drops its oldest lines.
 */
class Trace(val id: String) {
    val steps = LinkedHashMap<String?, Step>()
    val lines = ArrayList<LogLine>()

    /** The apps that wrote at least one of the lines. */
    val apps = HashSet<String>()
    private val levels = IntArray(Level.entries.size)

    /** The lines dropped to keep the request within [LINES_MAX]. */
    var dropped = 0
        private set

    /** [trim] runs once [lines] passes this; above [LINES_MAX] after a trim that could not get under it. */
    private var trimPast = LINES_MAX

    /** Counts the dropped lines too. */
    fun count(level: Level) = levels[level.ordinal]

    fun add(line: LogLine) {
        place(line)
        if (lines.size > trimPast) trim()
    }

    private fun place(line: LogLine) {
        lines += line
        apps += line.app
        levels[line.level.ordinal]++
        val s = steps.getOrPut(line.span) { Step(line.span).also { it.start = line.time; it.app = line.app } }
        when (line.phase) {
            Phase.START -> {
                s.start = line.time
                s.startLine = line
                s.parent = line.parent
                s.app = line.app
                s.event = line.event
            }
            Phase.END -> {
                s.end = line.time
                s.endLine = line
                if (s.startLine == null) {
                    s.start = line.time - (line.durationMs ?: 0)
                    s.parent = line.parent
                    s.app = line.app
                    s.event = line.event
                }
            }
            null -> s.notes += line
        }
    }

    /**
     * Drops the oldest lines, a tenth of [LINES_MAX] at a time. The lines inside the steps go first,
     * except those among the newest tenth, so the steps keep their START and END lines. When that is
     * not enough, the oldest finished steps go, but not one that others hang under or that holds one
     * of the newest lines. A step left with no line goes too. If the request is still too long, the
     * next trim waits for another tenth.
     */
    private fun trim() {
        var excess = lines.size - LINES_MAX + LINES_MAX / 10
        val gone = Collections.newSetFromMap(IdentityHashMap<LogLine, Boolean>())
        // the newest lines are the ones being read
        for (i in 0 until lines.size - LINES_MAX / 10) {
            if (excess <= 0) break
            val line = lines[i]
            if (line.phase == null) {
                gone += line
                excess--
            }
        }
        if (excess > 0) {
            val parents = steps.values.mapNotNullTo(HashSet()) { it.parent }
            val it = steps.values.iterator()
            while (excess > 0 && it.hasNext()) {
                val s = it.next()
                val end = s.endLine ?: continue
                // a step stays while others hang under it or it holds one of the newest lines
                if (s.id in parents || s.notes.any { it !in gone }) continue
                s.startLine?.let { start -> gone += start; excess-- }
                gone += end
                excess--
                it.remove()
            }
        }
        lines.removeIf { it in gone }
        for (s in steps.values) s.notes.removeIf { it in gone }
        steps.values.removeIf { it.startLine == null && it.endLine == null && it.notes.isEmpty() }
        dropped += gone.size
        // trying again on every line would scan them all each time and find nothing more to drop
        trimPast = if (lines.size > LINES_MAX) lines.size + LINES_MAX / 10 else LINES_MAX
    }

    class Shape(val kids: Map<String?, List<Step>>, val roots: List<Step>, val unfinished: Int, val running: Int)

    /** Links the steps to their parents and marks the ones that will not finish. */
    fun analyse(now: Long): Shape {
        val kids = HashMap<String?, MutableList<Step>>()
        val roots = ArrayList<Step>()
        for (s in steps.values) {
            val p = s.parent
            if (p != null && p != s.id && steps.containsKey(p)) kids.getOrPut(p) { ArrayList() } += s else roots += s
        }
        roots.sortBy { it.start }
        kids.values.forEach { list -> list.sortBy { it.start } }
        var unfinished = 0
        var running = 0
        fun visit(s: Step, parentClosed: Boolean) {
            // A synchronous child cannot outlive its parent. MQ consumers run on their own.
            val sync = s.event != "MQ_IN"
            s.unfinished = s.end == null && ((sync && parentClosed) || now - s.start > UNFINISHED_AFTER_MS)
            if (s.unfinished) unfinished++ else if (s.end == null) running++
            kids[s.id]?.forEach { visit(it, s.end != null || s.unfinished) }
        }
        roots.forEach { visit(it, false) }
        return Shape(kids, roots, unfinished, running)
    }

    /** The number of lines inside a step, its nested steps included. */
    fun blockCount(s: Step, shape: Shape): Int =
        (if (s.startLine != null) 1 else 0) + (if (s.endLine != null) 1 else 0) + s.notes.size +
            (shape.kids[s.id]?.sumOf { blockCount(it, shape) } ?: 0)

    companion object {
        const val UNFINISHED_AFTER_MS = 30_000L
        const val LINES_MAX = 5_000
    }
}
