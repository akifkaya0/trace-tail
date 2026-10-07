package io.github.akifkaya0.tracetail.agent.apps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.message.StringMapMessage;

/** Logs the cases the agent must handle on Log4j2, then reconfigures Log4j2 the way Spring Boot does. */
public final class Log4jApp {

    private static final Logger log = LogManager.getLogger(Log4jApp.class);

    public static void main(String[] args) {
        log.info("first line");
        ThreadContext.put("trace.id", "t-1");
        ThreadContext.put("span.id", "s-1");
        log.info(new StringMapMessage()
            .with("message", "request started")
            .with("event.action", "HTTP_IN")
            .with("phase", "START")
            .with("durationMs", 12));
        log.debug("below the level");
        log.error("failed", new IllegalStateException("boom"));
        ThreadContext.clearAll();

        // Spring Boot replaces the configuration with a new one.
        Configurator.reconfigure();
        log.info("after reconfiguring");
    }
}
