package io.github.akifkaya0.tracetail.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.LifeCycle;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;

/**
 * Logback creates this class at the start of each logger context's configuration, because the agent
 * names it in {@code logback.statusListenerClass}. It is a status listener only to get the context
 * that early. It adds the appender, then leaves the context's status listeners, so Logback still
 * prints its configuration errors as before.
 */
public final class LogbackHook extends ContextAwareBase implements StatusListener, LifeCycle {

    private boolean started;

    @Override
    public void setContext(Context context) {
        super.setContext(context);
        Sender sender = TraceTailAgent.sender;
        if (sender != null && context instanceof LoggerContext) {
            try {
                install((LoggerContext) context, sender);
            } catch (Throwable t) {
                TraceTailAgent.warn("Logback's lines are not sent: " + t);
            }
        }
    }

    @Override
    public void start() {
        started = true;
        // Logback prints its configuration's errors only when the context has no status listener.
        getContext().getStatusManager().remove(this);
    }

    @Override
    public void stop() {
        started = false;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public void addStatusEvent(Status status) {
    }

    /**
     * Puts the appender on the root logger inside an appender list that keeps it. Spring Boot stops
     * and resets the context before it configures it, and both remove every appender and listener.
     */
    @SuppressWarnings("unchecked")
    private static void install(LoggerContext context, Sender sender) throws ReflectiveOperationException {
        LogbackAppender appender = new LogbackAppender(sender);
        appender.setContext(context);
        appender.setName(TraceTailAgent.APPENDER_NAME);
        appender.start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Field field = Logger.class.getDeclaredField("aai");
        field.setAccessible(true);
        // Logger.addAppender holds the same lock while it creates the list.
        synchronized (root) {
            AppenderAttachableImpl<ILoggingEvent> old = (AppenderAttachableImpl<ILoggingEvent>) field.get(root);
            if (old instanceof KeepingAppenderList) {
                return;
            }
            KeepingAppenderList list = new KeepingAppenderList(appender);
            if (old != null) {
                for (Iterator<Appender<ILoggingEvent>> it = old.iteratorForAppenders(); it.hasNext(); ) {
                    list.addAppender(it.next());
                }
            }
            field.set(root, list);
        }
    }

    private static final class KeepingAppenderList extends AppenderAttachableImpl<ILoggingEvent> {

        private final Appender<ILoggingEvent> kept;

        KeepingAppenderList(Appender<ILoggingEvent> kept) {
            this.kept = kept;
            addAppender(kept);
        }

        @Override
        public void detachAndStopAllAppenders() {
            super.detachAndStopAllAppenders();
            addAppender(kept);
        }
    }

    private static final class LogbackAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

        private final Sender sender;

        LogbackAppender(Sender sender) {
            this.sender = sender;
        }

        /** Stays started when the context is reset; see {@link KeepingAppenderList}. */
        @Override
        public void stop() {
        }

        @Override
        protected void append(ILoggingEvent event) {
            try {
                sender.send(format(event));
            } catch (Throwable ignored) {
                // never break the application's logging
            }
        }

        private static String format(ILoggingEvent event) {
            JsonLine line = new JsonLine(
                event.getTimeStamp(),
                event.getLevel().toString(),
                event.getFormattedMessage(),
                event.getThreadName(),
                event.getLoggerName()
            );
            for (Map.Entry<String, String> e : event.getMDCPropertyMap().entrySet()) {
                line.add(e.getKey(), e.getValue());
            }
            KeyValues.addTo(line, event);
            IThrowableProxy thrown = event.getThrowableProxy();
            if (thrown != null) {
                line.error(thrown.getClassName(), thrown.getMessage(), ThrowableProxyUtil.asString(thrown));
            }
            return line.end();
        }
    }

    /** SLF4J 2's key-value pairs, which Logback 1.3 and later keep on the event. Read by reflection, for Logback 1.2. */
    private static final class KeyValues {

        private static final Method GET = method();
        private static volatile Field key;
        private static volatile Field value;

        static void addTo(JsonLine line, ILoggingEvent event) {
            if (GET == null) {
                return;
            }
            try {
                List<?> pairs = (List<?>) GET.invoke(event);
                if (pairs == null) {
                    return;
                }
                for (Object pair : pairs) {
                    if (key == null) {
                        value = pair.getClass().getField("value");
                        key = pair.getClass().getField("key");
                    }
                    line.add((String) key.get(pair), value.get(pair));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }

        private static Method method() {
            try {
                return ILoggingEvent.class.getMethod("getKeyValuePairs");
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    }
}
