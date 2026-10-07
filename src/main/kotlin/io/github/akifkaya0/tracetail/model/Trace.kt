package io.github.akifkaya0.tracetail.model

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

/** The lines of one request, grouped into steps by trace.id, span.id and parent.id. */
class Trace(val id: String) {
    val steps = LinkedHashMap<String?, Step>()
    val lines = mutableListOf<LogLine>()
    private val levels = IntArray(Level.entries.size)

    fun count(level: Level) = levels[level.ordinal]

    fun add(line: LogLine) {
        lines += line
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
    }
}
