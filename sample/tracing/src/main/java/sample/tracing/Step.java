package sample.tracing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * One step of a request, logged as a START line when it begins and an END line when it ends. While
 * the step runs, its id is the span id in the MDC, so every line written inside it carries the id.
 *
 * <p>A real application writes these lines from a filter, an interceptor or an aspect. The sample
 * writes them by hand to stay small.
 */
public final class Step {

    private static final String USER_KEY = "user.id";
    private static String traceKey = "trace.id";
    private static String spanKey = "span.id";

    private final Logger log;
    private final String event;
    private final String id = newId(16);
    private final String parent;
    private final Map<String, Object> fields = new LinkedHashMap<>();
    private final Map<String, Object> endFields = new LinkedHashMap<>();
    private final Map<String, String> saved = new LinkedHashMap<>();
    private final long start = System.currentTimeMillis();
    private Level level = Level.INFO;
    private String outcome = "SUCCESS";
    private Throwable error;
    private boolean abandoned;

    private Step(Logger log, String event, Caller caller, Object... fields) {
        this.log = log;
        this.event = event;
        for (int i = 0; i + 1 < fields.length; i += 2) {
            this.fields.put(String.valueOf(fields[i]), fields[i + 1]);
        }
        for (String key : new String[] {traceKey, spanKey, USER_KEY}) {
            saved.put(key, MDC.get(key));
        }
        if (caller == null) {
            parent = MDC.get(spanKey);
        } else {
            parent = caller.parent();
            MDC.put(traceKey, caller.trace() != null ? caller.trace() : newId(32));
            if (caller.user() != null) {
                MDC.put(USER_KEY, caller.user());
            } else {
                MDC.remove(USER_KEY);
            }
        }
        MDC.put(spanKey, id);
        write(Level.INFO, "START", "Started", Map.of(), null);
    }

    /** The stock app keeps its ids under Micrometer's names, which the view also reads. */
    public static void useMicrometerNames() {
        traceKey = "traceId";
        spanKey = "spanId";
    }

    /** Starts the first step of a request in this app. */
    public static Step request(Logger log, String event, Caller caller, Object... fields) {
        return new Step(log, event, caller, fields);
    }

    /** Starts a step inside the current request. */
    public static Step start(Logger log, String event, Object... fields) {
        return new Step(log, event, null, fields);
    }

    /** Starts a METHOD step named after the method that calls this, as an aspect would. */
    public static Step method(Logger log) {
        StackWalker.StackFrame caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.dropWhile(f -> f.getDeclaringClass() == Step.class).findFirst())
                .orElseThrow();
        return new Step(log, "METHOD", null, "method", caller.getDeclaringClass().getSimpleName() + "." + caller.getMethodName());
    }

    public static String traceId() {
        return MDC.get(traceKey);
    }

    public static String userId() {
        return MDC.get(USER_KEY);
    }

    public String id() {
        return id;
    }

    /** Adds a field to the END line. */
    public Step field(String key, Object value) {
        endFields.put(key, value);
        return this;
    }

    public Step outcome(String outcome) {
        this.outcome = outcome;
        return this;
    }

    /** The END line's level; INFO unless set. */
    public Step level(Level level) {
        this.level = level;
        return this;
    }

    /** Ends the step as failed by an unexpected error: an ERROR line with the stack trace. */
    public Step error(Throwable e) {
        error = e;
        outcome = "FAILURE";
        level = Level.ERROR;
        return field("exception", e.getClass().getSimpleName());
    }

    /** Leaves out the END line, as when the process dies in the middle of the step. */
    public void abandon() {
        abandoned = true;
    }

    /**
     * Runs [body] as this step and writes the END line. An exception that leaves [body] fails the
     * step with a WARN line; a body that set the outcome has described the failure itself.
     */
    public <T> T call(Function<Step, T> body) {
        try {
            T result = body.apply(this);
            end();
            return result;
        } catch (RuntimeException | Error e) {
            if (outcome.equals("SUCCESS")) {
                outcome = "FAILURE";
                field("exception", e.getClass().getSimpleName());
            }
            if (level.toInt() < Level.WARN.toInt()) {
                level = Level.WARN;
            }
            end();
            throw e;
        }
    }

    public void run(Consumer<Step> body) {
        call(step -> {
            body.accept(step);
            return null;
        });
    }

    private void end() {
        if (!abandoned) {
            Map<String, Object> end = new LinkedHashMap<>(endFields);
            end.put("outcome", outcome);
            long now = System.currentTimeMillis();
            end.put("durationMs", now - start);
            end.put("startTime", Instant.ofEpochMilli(start).toString());
            write(level, "END", outcome.equals("SUCCESS") ? "Finished" : "Ended with " + outcome, end, error);
        }
        saved.forEach((key, value) -> {
            if (value != null) {
                MDC.put(key, value);
            } else {
                MDC.remove(key);
            }
        });
    }

    private void write(Level level, String phase, String message, Map<String, Object> extra, Throwable cause) {
        LoggingEventBuilder line = log.atLevel(level)
                .addKeyValue("event.action", event)
                .addKeyValue("phase", phase);
        if (parent != null) {
            line.addKeyValue("parent.id", parent);
        }
        fields.forEach(line::addKeyValue);
        extra.forEach(line::addKeyValue);
        if (cause != null) {
            line.setCause(cause);
        }
        line.log(message);
    }

    private static String newId(int hexDigits) {
        StringBuilder sb = new StringBuilder(hexDigits);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < hexDigits; i++) {
            sb.append(Character.forDigit(random.nextInt(16), 16));
        }
        return sb.toString();
    }
}
