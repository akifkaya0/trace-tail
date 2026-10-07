package io.github.akifkaya0.tracetail.agent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

/** One log event as a single-line JSON object with ECS field names. A null value leaves its field out. */
final class JsonLine {

    private final StringBuilder sb = new StringBuilder(256).append('{');

    /** The fields every event has, in the order the ECS layout writes them. */
    JsonLine(long millis, String level, String message, String thread, String logger) {
        add("@timestamp", Instant.ofEpochMilli(millis).toString());
        add("log.level", level);
        add("message", message);
        add("service.name", TraceTailAgent.app);
        add("process.thread.name", thread);
        add("log.logger", logger);
    }

    JsonLine origin(StackTraceElement source) {
        if (source != null) {
            add("log.origin.file.name", source.getFileName());
            add("log.origin.function", source.getMethodName());
            if (source.getLineNumber() > 0) {
                key("log.origin.file.line").append(source.getLineNumber());
            }
        }
        return this;
    }

    JsonLine error(String type, String message, String stackTrace) {
        add("error.type", type);
        add("error.message", message);
        // Windows line ends and Logback's trailing line end would show as blank lines in the IDE.
        add("error.stack_trace", stackTrace.replace("\r\n", "\n").trim());
        return this;
    }

    JsonLine add(String key, Object value) {
        if (key != null && value != null) {
            quote(key(key), String.valueOf(value));
        }
        return this;
    }

    String end() {
        return sb.append('}').toString();
    }

    static String stackTrace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private StringBuilder key(String key) {
        if (sb.length() > 1) {
            sb.append(',');
        }
        return quote(sb, key).append(':');
    }

    private static StringBuilder quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"');
    }
}
