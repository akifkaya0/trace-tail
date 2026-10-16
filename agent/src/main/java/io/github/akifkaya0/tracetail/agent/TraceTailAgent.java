package io.github.akifkaya0.tracetail.agent;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;

/**
 * Sends an application's log events to Trace Tail without any change to the application. The
 * plugin starts the application with {@code -javaagent:trace-tail-agent.jar=<port>,<app>}.
 *
 * <p>The agent never starts the logging framework itself. It waits for the framework to start and
 * then adds an appender that writes each event as one ECS JSON line to the plugin's port:
 * <ul>
 *   <li>Logback creates {@link LogbackHook} at the start of its configuration, because the agent
 *       names it in {@code logback.statusListenerClass}.</li>
 *   <li>Log4j2 asks {@link Log4jHook} for context data on every event, because the agent jar lists
 *       it as a service.</li>
 * </ul>
 */
public final class TraceTailAgent {

    static final String APPENDER_NAME = "TraceTail";
    private static final String LOGBACK_STATUS_LISTENER = "logback.statusListenerClass";

    /** Null when the jar is on the class path without {@code -javaagent}; the hooks then do nothing. */
    static volatile Sender sender;
    static volatile String app;

    /** Tells the plugin which run a line came from; two runs of one configuration share its name. */
    static volatile String pid;

    private TraceTailAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        try {
            int comma = args == null ? -1 : args.indexOf(',');
            if (comma < 0) {
                warn("expected <port>,<app> as the agent's arguments, got " + args);
                return;
            }
            int port = Integer.parseInt(args.substring(0, comma).trim());
            app = args.substring(comma + 1);
            pid = pid();
            sender = new Sender(port);
            if (System.getProperty(LOGBACK_STATUS_LISTENER) == null) {
                System.setProperty(LOGBACK_STATUS_LISTENER, "io.github.akifkaya0.tracetail.agent.LogbackHook");
            } else {
                warn(LOGBACK_STATUS_LISTENER + " is already set, so Logback's lines are not sent");
            }
        } catch (Throwable t) {
            warn("not started: " + t);
        }
    }

    /** ProcessHandle came with Java 9, so it is called by reflection. */
    static String pid() {
        try {
            Class<?> handle = Class.forName("java.lang.ProcessHandle");
            return String.valueOf(handle.getMethod("pid").invoke(handle.getMethod("current").invoke(null)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return runtimePid();
        }
    }

    /** On Java 8, the runtime's name is the process id and the host name, such as {@code 1234@host}. */
    static String runtimePid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : null;
    }

    static void warn(String message) {
        System.err.println("[trace-tail] " + message);
    }
}
