package io.github.akifkaya0.tracetail.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The agent in an application on the oldest and the newest Logback and Log4j2 it supports. The
 * applications log the same cases, then reconfigure their logging the way Spring Boot does.
 */
class AgentTest {

    private static final String LOGBACK_APP = "io.github.akifkaya0.tracetail.agent.apps.LogbackApp";
    private static final String LOG4J_APP = "io.github.akifkaya0.tracetail.agent.apps.Log4jApp";

    static Stream<String> setups() {
        return Stream.of("logback12", "logback15", "log4j217", "log4j224");
    }

    static Stream<String> logbackSetups() {
        return Stream.of("logback12", "logback15");
    }

    /** The setups that log structured arguments: SLF4J 2's key-value pairs on Logback, a map message on Log4j2. */
    static Stream<String> structuredSetups() {
        return Stream.of("logback15", "log4j217", "log4j224");
    }

    @ParameterizedTest
    @MethodSource("setups")
    void sendsTheFirstLine(String setup) {
        Map<String, String> line = run(setup).line("first line");
        assertEquals("INFO", line.get("log.level"));
        assertEquals(AppRun.APP, line.get("service.name"));
        assertEquals("main", line.get("process.thread.name"));
        assertEquals(app(setup), line.get("log.logger"));
        assertDoesNotThrow(() -> Instant.parse(line.get("@timestamp")));
    }

    @ParameterizedTest
    @MethodSource("setups")
    void leavesOutWhereTheLineWasWritten(String setup) {
        // finding it would make each log call 8 to 30 times slower
        Map<String, String> line = run(setup).line("first line");
        assertFalse(line.keySet().stream().anyMatch(key -> key.startsWith("log.origin.")), line.toString());
    }

    @ParameterizedTest
    @MethodSource("setups")
    void writesTheThreadContextAsFields(String setup) {
        Map<String, String> line = run(setup).line("failed");
        assertEquals("t-1", line.get("trace.id"));
        assertEquals("s-1", line.get("span.id"));
    }

    @ParameterizedTest
    @MethodSource("structuredSetups")
    void writesStructuredArgumentsAsFields(String setup) {
        Map<String, String> line = run(setup).line("request started");
        assertEquals("HTTP_IN", line.get("event.action"));
        assertEquals("START", line.get("phase"));
        assertEquals("12", line.get("durationMs"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    void sendsOnlyWhatTheLoggersLevelLetsThrough(String setup) {
        assertFalse(run(setup).has("below the level"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    void sendsTheStackTrace(String setup) {
        Map<String, String> line = run(setup).line("failed");
        assertEquals("ERROR", line.get("log.level"));
        assertEquals("java.lang.IllegalStateException", line.get("error.type"));
        assertEquals("boom", line.get("error.message"));
        String trace = line.get("error.stack_trace");
        assertTrue(trace.startsWith("java.lang.IllegalStateException: boom\n\tat " + app(setup) + ".main("), trace);
        assertFalse(trace.contains("\r") || trace.endsWith("\n"), trace);
    }

    @ParameterizedTest
    @MethodSource("setups")
    void keepsSendingAfterTheLoggingIsReconfigured(String setup) {
        run(setup).line("after reconfiguring");
    }

    @ParameterizedTest
    @MethodSource("setups")
    void leavesTheApplicationsOwnAppenderInPlace(String setup) {
        String console = run(setup).console;
        assertTrue(console.contains("CONSOLE INFO  first line"), console);
        assertTrue(console.contains("CONSOLE INFO  after reconfiguring"), console);
    }

    @ParameterizedTest
    @MethodSource("logbackSetups")
    void leavesLogbacksConfigurationErrorsPrinted(String setup, @TempDir Path dir) throws IOException {
        Path config = dir.resolve("logback.xml");
        Files.writeString(config, """
            <configuration>
                <appender name="Broken" class="does.not.Exist"/>
                <root level="info">
                    <appender-ref ref="Broken"/>
                </root>
            </configuration>
            """);
        AppRun run = AppRun.start(setup, List.of("-Dlogback.configurationFile=" + config), LOGBACK_APP);
        assertTrue(run.console.contains("does.not.Exist"), run.console);
        run.line("first line");
    }

    private static AppRun run(String setup) {
        return AppRun.shared(setup, app(setup));
    }

    private static String app(String setup) {
        return setup.startsWith("logback") ? LOGBACK_APP : LOG4J_APP;
    }
}
