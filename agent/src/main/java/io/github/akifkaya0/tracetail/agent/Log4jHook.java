package io.github.akifkaya0.tracetail.agent;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.ErrorHandler;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.AbstractLifeCycle;
import org.apache.logging.log4j.core.appender.DefaultErrorHandler;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.core.util.ContextDataProvider;
import org.apache.logging.log4j.message.MapMessage;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.spi.LoggerContextFactory;

/**
 * Log4j2 asks this provider for context data while it builds each event, before the event reaches
 * any appender. The agent uses the call to add its appender to the current configuration, so the
 * first event is sent too, and so is every event after a reconfiguration, such as Spring Boot's.
 * It adds no data of its own.
 */
public final class Log4jHook implements ContextDataProvider {

    private static final long RESCAN_NANOS = 1_000_000_000L;

    private static volatile List<LoggerContext> contexts = Collections.emptyList();
    private static volatile long nextScan;

    @Override
    public Map<String, String> supplyContextData() {
        try {
            check();
        } catch (Throwable ignored) {
            // never break the application's logging
        }
        return Collections.emptyMap();
    }

    private static void check() {
        Sender sender = TraceTailAgent.sender;
        if (sender == null) {
            return;
        }
        long now = System.nanoTime();
        if (contexts.isEmpty() || now - nextScan >= 0) {
            nextScan = now + RESCAN_NANOS;
            LoggerContextFactory factory = LogManager.getFactory();
            if (factory instanceof Log4jContextFactory) {
                contexts = ((Log4jContextFactory) factory).getSelector().getLoggerContexts();
            }
        }
        for (LoggerContext context : contexts) {
            Configuration config = context.getConfiguration();
            if (config.getAppender(TraceTailAgent.APPENDER_NAME) == null) {
                attach(config, sender);
            }
        }
    }

    /** A new appender for each configuration, because a configuration stops its appenders when it is replaced. */
    private static synchronized void attach(Configuration config, Sender sender) {
        if (config.getAppender(TraceTailAgent.APPENDER_NAME) != null) {
            return;
        }
        Log4jAppender appender = new Log4jAppender(sender);
        appender.start();
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, null, null);
    }

    private static final class Log4jAppender extends AbstractLifeCycle implements Appender {

        private final Sender sender;
        private ErrorHandler handler = new DefaultErrorHandler(this);

        Log4jAppender(Sender sender) {
            this.sender = sender;
        }

        @Override
        public void append(LogEvent event) {
            try {
                sender.send(format(event));
            } catch (Throwable ignored) {
                // never break the application's logging
            }
        }

        private static String format(LogEvent event) {
            Message message = event.getMessage();
            // A map message's entries are fields, as in the ECS layout; its "message" entry is the message.
            Map<String, ?> entries = message instanceof MapMessage ? ((MapMessage<?, ?>) message).getData() : null;
            JsonLine line = new JsonLine(
                event.getTimeMillis(),
                event.getLevel().name(),
                entries != null ? str(entries.get("message")) : message.getFormattedMessage(),
                event.getThreadName(),
                event.getLoggerName()
            ).origin(event.getSource());
            for (Map.Entry<String, String> e : event.getContextData().toMap().entrySet()) {
                line.add(e.getKey(), e.getValue());
            }
            if (entries != null) {
                for (Map.Entry<String, ?> e : entries.entrySet()) {
                    if (!"message".equals(e.getKey())) {
                        line.add(e.getKey(), e.getValue());
                    }
                }
            }
            Throwable thrown = event.getThrown();
            if (thrown != null) {
                line.error(thrown.getClass().getName(), thrown.getMessage(), JsonLine.stackTrace(thrown));
            }
            return line.end();
        }

        private static String str(Object value) {
            return value == null ? null : value.toString();
        }

        @Override
        public String getName() {
            return TraceTailAgent.APPENDER_NAME;
        }

        @Override
        public Layout<? extends Serializable> getLayout() {
            return null;
        }

        @Override
        public boolean ignoreExceptions() {
            return true;
        }

        @Override
        public ErrorHandler getHandler() {
            return handler;
        }

        @Override
        public void setHandler(ErrorHandler handler) {
            this.handler = handler;
        }
    }
}
