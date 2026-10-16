package io.github.akifkaya0.tracetail.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TraceTailAgentTest {

    @Test
    void readsTheProcessIdTheWayJava8Can() {
        assertEquals(String.valueOf(ProcessHandle.current().pid()), TraceTailAgent.runtimePid());
    }
}
