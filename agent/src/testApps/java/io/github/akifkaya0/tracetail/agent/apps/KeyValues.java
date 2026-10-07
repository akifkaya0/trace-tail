package io.github.akifkaya0.tracetail.agent.apps;

import org.slf4j.Logger;

/** SLF4J 2's key-value pairs, in a class of their own, so that with SLF4J 1 it is never loaded. */
final class KeyValues {

    private KeyValues() {
    }

    static void log(Logger log) {
        log.atInfo()
            .addKeyValue("event.action", "HTTP_IN")
            .addKeyValue("phase", "START")
            .addKeyValue("durationMs", 12)
            .log("request started");
    }
}
