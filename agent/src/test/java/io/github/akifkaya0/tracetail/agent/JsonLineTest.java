package io.github.akifkaya0.tracetail.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonLineTest {

    @Test
    void escapesTheCharactersJsonDoesNotAllow() {
        String message = "quote \" backslash \\ line\nnext\r tab\t bell \u0007 letters üğış";
        Map<String, String> line = AppRun.fields(new JsonLine(0, "INFO", message, "main", "a.B").end());
        assertEquals(message, line.get("message"));
    }

    @Test
    void leavesOutTheFieldsWithoutAValue() {
        Map<String, String> line = AppRun.fields(new JsonLine(0, "INFO", "m", null, "a.B").add("user.id", null).end());
        assertFalse(line.containsKey("process.thread.name"));
        assertFalse(line.containsKey("user.id"));
    }

    @Test
    void writesTheStackTraceWithoutWindowsLineEndsOrATrailingOne() {
        String trace = "java.lang.IllegalStateException: boom\r\n\tat a.B.c(B.java:1)\r\n";
        Map<String, String> line = AppRun.fields(new JsonLine(0, "ERROR", "m", "main", "a.B")
            .error("java.lang.IllegalStateException", "boom", trace)
            .end());
        assertEquals("java.lang.IllegalStateException: boom\n\tat a.B.c(B.java:1)", line.get("error.stack_trace"));
    }
}
