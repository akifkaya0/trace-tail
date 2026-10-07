package io.github.akifkaya0.tracetail.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The agent in a Spring Boot application, which stops and reconfigures Logback or Log4j2 as it starts. */
class SpringBootTest {

    private static final String BOOT_APP = "io.github.akifkaya0.tracetail.agent.apps.BootApp";

    static Stream<String> setups() {
        return Stream.of("bootLogback", "bootLog4j2");
    }

    @ParameterizedTest
    @MethodSource("setups")
    void sendsSpringBootsOwnLines(String setup) {
        assertEquals(BOOT_APP, run(setup).line("Starting BootApp").get("log.logger"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    void writesMicrometersIdsAsFields(String setup) {
        Map<String, String> line = run(setup).line("runner line");
        assertEquals("t-1", line.get("traceId"));
        assertEquals("s-1", line.get("spanId"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    void sendsTheStackTrace(String setup) {
        Map<String, String> line = run(setup).line("runner warning");
        assertEquals("WARN", line.get("log.level"));
        assertEquals("java.lang.IllegalStateException", line.get("error.type"));
    }

    @ParameterizedTest
    @MethodSource("setups")
    void leavesTheApplicationsOwnAppenderInPlace(String setup) {
        String console = run(setup).console;
        assertTrue(console.contains("CONSOLE INFO  runner line"), console);
    }

    private static AppRun run(String setup) {
        return AppRun.shared(setup, BOOT_APP);
    }
}
