package io.github.akifkaya0.tracetail.ui

import io.github.akifkaya0.tracetail.model.LogLine
import io.github.akifkaya0.tracetail.model.Phase
import io.github.akifkaya0.tracetail.model.Step
import io.github.akifkaya0.tracetail.model.Trace

/**
 * One request as a sequence diagram: its lifelines, and a row for every call, return and note in
 * the order the lines were written. Needs [Trace.analyse] to have run, for the steps that will not
 * finish. A lifeline's key is `app:<name>`, `cls:<class>` for a method's class, or `ext:<name>`
 * for a party that writes no lines, such as the user.
 */
internal class Sequence(val trace: Trace, isApp: (String) -> Boolean) {

    class Part(val key: String, val group: String, val ext: Boolean, val app: String?, val label: String)

    sealed class Row(val line: LogLine, val step: Step)
    class Call(line: LogLine, step: Step, val from: String, val to: String) : Row(line, step)
    class Return(line: LogLine, step: Step, val from: String, val to: String) : Row(line, step)
    class Note(line: LogLine, step: Step, val on: String) : Row(line, step)

    /** The bar on a lifeline while a step runs, from row [r0] to row [r1]. */
    class Activation(val step: Step, val on: String, val r0: Int, val r1: Int?, val lost: Boolean, val running: Boolean) {
        var depth = 0
    }

    val rows = ArrayList<Row>()
    val activations = ArrayList<Activation>()
    val activationOf = HashMap<Step, Activation>()
    val parts = ArrayList<Part>()

    init {
        val steps = trace.steps
        // An HTTP_IN whose parent is an HTTP_OUT is the far end of that call: one call, drawn once.
        val mergedChildOf = HashMap<Step, Step>()
        for (s in steps.values) mergedParent(s)?.let { mergedChildOf[it] = s }

        fun owner(s: Step): String = when (s.event) {
            "HTTP_OUT" -> fields(s)["target"].orEmpty().let { (if (isApp(it)) "app:" else "ext:") + it }
            "MQ_OUT" -> QUEUE
            "METHOD" -> "cls:" + fields(s)["method"].orEmpty().substringBefore('.')
            else -> "app:" + s.app
        }

        fun caller(s: Step): String = parentOf(s)?.let(::owner) ?: when (s.event) {
            "HTTP_IN" -> USER
            "JOB" -> SCHEDULER
            "MQ_IN" -> QUEUE
            else -> "app:" + s.app
        }

        val r0 = HashMap<Step, Int>()
        val r1 = HashMap<Step, Int>()
        for (line in trace.lines) {
            val s = steps[line.span] ?: continue
            when (line.phase) {
                Phase.START -> {
                    val p = mergedParent(s)
                    if (p != null) {
                        r0[p]?.let { r0[s] = it }
                        continue
                    }
                    r0[s] = rows.size
                    rows += Call(line, s, caller(s), owner(s))
                }
                Phase.END -> {
                    if (mergedParent(s) != null) continue
                    r1[s] = rows.size
                    rows += Return(line, s, owner(s), caller(s))
                }
                null -> rows += Note(line, s, owner(s))
            }
        }

        fun ownEnd(s: Step): Int? = mergedParent(s)?.let { r1[it] } ?: r1[s]
        for (s in steps.values) {
            val start = r0[s] ?: continue
            if (s in mergedChildOf) continue
            var end = ownEnd(s)
            if (end == null && s.unfinished) {
                // nobody wrote its END: the bar stops where the nearest finished caller returned
                var p = parentOf(s)
                var hops = 0
                while (p != null && end == null && hops++ < steps.size) {
                    end = ownEnd(p)
                    p = parentOf(p)
                }
            }
            val a = Activation(s, owner(s), start, end, s.unfinished, s.end == null && !s.unfinished)
            activations += a
            activationOf[s] = a
        }
        for ((parent, child) in mergedChildOf) activationOf[child]?.let { activationOf[parent] = it }
        activations.sortBy { it.r0 }
        val live = HashMap<String, MutableList<Int>>()
        for (a in activations) {
            val ends = live.getOrPut(a.on) { ArrayList() }
            ends.removeAll { it <= a.r0 }
            a.depth = ends.size
            ends += a.r1 ?: Int.MAX_VALUE
        }

        // lifelines in order of first appearance, an app's classes kept next to the app
        val keys = LinkedHashSet<String>()
        for (r in rows) {
            when (r) {
                is Note -> keys += r.on
                is Call -> { keys += r.from; keys += r.to }
                is Return -> { keys += r.from; keys += r.to }
            }
        }
        activations.forEach { keys += it.on }
        val classApp = HashMap<String, String>()
        for (s in steps.values) if (s.event == "METHOD") classApp[owner(s)] = s.app.orEmpty()
        val members = LinkedHashMap<String, MutableList<String>>()
        for (k in keys) {
            val group = when {
                k.startsWith("app:") -> k.drop(4)
                k.startsWith("cls:") -> classApp[k] ?: k
                else -> k
            }
            members.getOrPut(group) { ArrayList() } += k
        }
        for ((group, ks) in members) {
            val lead = ks.indexOf("app:$group")
            if (lead > 0) ks.add(0, ks.removeAt(lead))
            for (k in ks) {
                val ext = k.startsWith("ext:")
                parts += Part(k, group, ext, if (ext) null else group, EXT_LABEL[k] ?: k.drop(4))
            }
        }
    }

    fun parentOf(s: Step): Step? = s.parent?.let { trace.steps[it] }?.takeIf { it !== s }

    private fun mergedParent(s: Step): Step? {
        val p = parentOf(s) ?: return null
        return if (s.event == "HTTP_IN" && p.event == "HTTP_OUT" && p.startLine != null) p else null
    }

    /* ---------- labels ---------- */

    fun callLabel(r: Call): String {
        val f = r.line.fields
        return when (r.step.event) {
            "HTTP_IN" -> f["route"] ?: "HTTP"
            "HTTP_OUT" -> f["op"] ?: "call"
            "MQ_OUT", "MQ_IN" -> f["queue"] ?: "queue"
            "JOB" -> f["job"] ?: "job"
            "METHOD" -> f["method"].orEmpty().substringAfter('.') + "()"
            else -> r.line.event ?: "…"
        }
    }

    /** The return's text, and the outcome it reports when that is worth a colour. */
    fun returnLabel(r: Return): Pair<String, String?> {
        val f = r.line.fields
        val outcome = f["outcome"]
        val failed = outcome?.takeIf { it != "SUCCESS" }
        val dur = r.line.durationMs?.let { " · " + Palette.duration(it) }.orEmpty()
        return when (r.step.event) {
            "METHOD" -> f["exception"]?.let { "✕ $it$dur" to (outcome ?: "FAILURE") } ?: ("returned$dur" to null)
            "MQ_OUT" -> "queued$dur" to null
            "MQ_IN" -> "ack$dur" to failed
            "JOB" -> "done$dur" to failed
            else -> (listOfNotNull(f["status"], outcome).joinToString(" ").ifEmpty { "response" } + dur) to outcome
        }
    }

    /** A note is kept short: the event and its first two fields. */
    fun noteText(r: Note): String {
        val f = r.line.fields.filterKeys { it != "startTime" }.entries.take(2).joinToString(" ") { "${it.key}=${it.value}" }
        return r.line.title + if (f.isEmpty()) "" else " $f"
    }

    fun mermaid(): String {
        val id = parts.withIndex().associate { (i, p) -> p.key to "P$i" }
        fun clean(t: String) = t.replace(Regex("[;#\\r\\n]+"), " ").replace(Regex("\\s+"), " ").trim()
        val out = mutableListOf("sequenceDiagram")
        val size = parts.groupingBy { it.group }.eachCount()
        var group: String? = null
        var inBox = false
        for (p in parts) {
            if (p.group != group) {
                if (inBox) out += "    end"
                inBox = false
                group = p.group
                if (!p.ext && (size[group] ?: 0) > 1) {
                    out += "    box rgb(244,247,250) " + clean(group)
                    inBox = true
                }
            }
            out += "    " + (if (p.key == USER) "actor" else "participant") + " " + id[p.key] + " as " + clean(p.label)
        }
        if (inBox) out += "    end"
        val active = HashMap<String, Int>()   // only deactivate what was activated, Mermaid rejects the rest
        for (r in rows) {
            when (r) {
                is Note -> out += "    Note right of ${id[r.on]}: " + clean(noteText(r))
                is Call -> {
                    out += "    ${id[r.from]}" + (if (r.step.event == "MQ_IN") "-)" else "->>") + "${id[r.to]}: " + clean(callLabel(r))
                    if (r.step in activationOf) {
                        out += "    activate ${id[r.to]}"
                        active.merge(r.to, 1, Int::plus)
                    }
                }
                is Return -> {
                    out += "    ${id[r.from]}-->>${id[r.to]}: " + clean(returnLabel(r).first)
                    if (r.step in activationOf && (active[r.from] ?: 0) > 0) {
                        out += "    deactivate ${id[r.from]}"
                        active.merge(r.from, -1, Int::plus)
                    }
                }
            }
        }
        for (a in activations) if (a.lost) out += "    Note over ${id[a.on]}: unfinished"
        return out.joinToString("\n")
    }

    private fun fields(s: Step) = (s.startLine ?: s.endLine)?.fields.orEmpty()

    companion object {
        const val USER = "ext:User"
        const val SCHEDULER = "ext:Scheduler"
        const val QUEUE = "ext:Queue"
        private val EXT_LABEL = mapOf(USER to "User", SCHEDULER to "Scheduler", QUEUE to "Queue")
    }
}
