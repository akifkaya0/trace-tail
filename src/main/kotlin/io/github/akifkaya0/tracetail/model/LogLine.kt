package io.github.akifkaya0.tracetail.model

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** The view knows four levels. TRACE shows as DEBUG and FATAL as ERROR; the JSON keeps the original. */
enum class Level {
    DEBUG, INFO, WARN, ERROR;

    companion object {
        fun of(name: String?): Level = when (name?.uppercase()) {
            "TRACE", "DEBUG" -> DEBUG
            "WARN" -> WARN
            "ERROR", "FATAL" -> ERROR
            else -> INFO
        }
    }
}

enum class Phase { START, END }

/** One received log line: an ECS JSON object, read into the fields the view works with. */
class LogLine(
    /** Unique per received line; a line moved under another step keeps it. */
    val seq: Long,
    val time: Long,
    val level: Level,
    val app: String,
    val trace: String?,
    val span: String?,
    val parent: String?,
    val event: String?,
    val phase: Phase?,
    val user: String?,
    val message: String,
    /** The fields without a place of their own, in the order the app wrote them. */
    val fields: Map<String, String>,
    val logger: String?,
    val stackTrace: String?,
    val json: String,
) {
    val outcome: String? get() = fields["outcome"]
    val status: String? get() = fields["status"]
    val durationMs: Long? get() = fields["durationMs"]?.toDoubleOrNull()?.toLong()

    /** The event's name, or the message for a line without one, such as a library's own log. */
    val title: String get() = event ?: message

    /** The same line hung under another step, because the step it names is hidden by the level filter. */
    fun placed(span: String?, parent: String?) = LogLine(
        seq, time, level, app, trace, span, parent, event, phase, user, message, fields, logger, stackTrace, json,
    )

    /** The JSON, indented, without the stack trace, which is shown on its own. */
    fun prettyJson(): String {
        val o = JsonParser.parseString(json).asJsonObject
        o.remove(STACK_TRACE)
        return PRETTY.toJson(o)
    }

    companion object {
        private const val STACK_TRACE = "error.stack_trace"
        private val OWN_FIELDS = setOf(
            "@timestamp", "log.level", "message", "ecs.version", "service.name", "event.dataset", "host.name",
            "process.thread.name", "log.logger",
            "trace.id", "span.id", "parent.id", "traceId", "spanId", "event.action", "phase", "flow", "user.id",
            STACK_TRACE,
        )
        private val PRETTY = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        private val counter = AtomicLong()

        /** Reads one line, or returns null when it is not a JSON object. */
        fun parse(text: String): LogLine? {
            val o = try {
                JsonParser.parseString(text) as? JsonObject
            } catch (_: JsonParseException) {
                null
            } ?: return null

            fun str(key: String): String? = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }

            val fields = LinkedHashMap<String, String>()
            for ((key, value) in o.entrySet()) {
                if (key !in OWN_FIELDS && !value.isJsonNull) fields[key] = value.text()
            }
            return LogLine(
                seq = counter.incrementAndGet(),
                time = str("@timestamp")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    ?: System.currentTimeMillis(),
                level = Level.of(str("log.level")),
                app = str("service.name") ?: "?",
                // Micrometer also puts the ids into the MDC under these names
                trace = str("trace.id") ?: str("traceId"),
                span = str("span.id") ?: str("spanId"),
                parent = str("parent.id"),
                event = str("event.action"),
                phase = when (str("phase")) {
                    "START" -> Phase.START
                    "END" -> Phase.END
                    else -> null
                },
                user = str("user.id"),
                message = str("message") ?: "",
                fields = fields,
                logger = str("log.logger"),
                stackTrace = str(STACK_TRACE),
                json = text,
            )
        }

        private fun JsonElement.text(): String = if (isJsonPrimitive) asString else toString()
    }
}
