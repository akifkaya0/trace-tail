package io.github.akifkaya0.tracetail.model

import java.time.Instant

/** The requests under `requests/`, one JSONL file each, written as the agent sends their lines. */
internal object Requests {

    fun lines(name: String): List<LogLine> {
        val text = Requests::class.java.getResource("/requests/$name.jsonl")?.readText() ?: error("no request named $name")
        return text.lines().filter { it.isNotBlank() }.map { line(it) }
    }

    /** The request's [Trace], built by a model that shows every level. */
    fun trace(name: String): Trace = TraceModel().also { it.add(lines(name)) }.traces.getValue(name)

    fun line(json: String): LogLine = LogLine.parse(json) ?: error("not a JSON object: $json")

    /** A time on the day the requests were written, such as `10:00:01`. */
    fun at(time: String): Long = Instant.parse("2026-10-07T${time}Z").toEpochMilli()
}
