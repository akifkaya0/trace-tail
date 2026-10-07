package io.github.akifkaya0.tracetail.agent.apps;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Logs the cases the agent must handle on Logback, then reconfigures Logback the way Spring Boot does. */
public final class LogbackApp {

    private static final Logger log = LoggerFactory.getLogger(LogbackApp.class);

    public static void main(String[] args) throws Exception {
        log.info("first line");
        MDC.put("trace.id", "t-1");
        MDC.put("span.id", "s-1");
        // Logback 1.2 comes with SLF4J 1, which has no key-value pairs.
        if (hasKeyValues()) {
            KeyValues.log(log);
        }
        log.debug("below the level");
        log.error("failed", new IllegalStateException("boom"));
        MDC.clear();

        // Spring Boot stops and resets the context, then configures it again.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.stop();
        context.reset();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(LogbackApp.class.getResource("/logback.xml"));
        context.start();
        log.info("after reconfiguring");
    }

    private static boolean hasKeyValues() {
        try {
            Logger.class.getMethod("atInfo");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
