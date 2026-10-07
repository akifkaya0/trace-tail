package io.github.akifkaya0.tracetail.agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * One of the test applications, run in a JVM of its own with the agent, as the plugin starts it:
 * the lines the agent sent and what the application printed. The build passes the agent jar and
 * each setup's class path as system properties.
 */
final class AppRun {

    /** The name the agent sends as {@code service.name}; the plugin passes the run configuration's. */
    static final String APP = "test-app";

    private static final int TIMEOUT_MILLIS = 60_000;
    private static final Map<String, AppRun> SHARED = new ConcurrentHashMap<>();

    final List<Map<String, String>> lines;
    final String console;

    private AppRun(List<Map<String, String>> lines, String console) {
        this.lines = lines;
        this.console = console;
    }

    /** A run that the tests of a setup share, since each run starts a JVM. */
    static AppRun shared(String setup, String main) {
        return SHARED.computeIfAbsent(setup + " " + main, key -> start(setup, List.of(), main));
    }

    static AppRun start(String setup, List<String> jvmArgs, String main) {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-javaagent:" + property("tracetail.agent") + "=" + server.getLocalPort() + "," + APP);
            command.addAll(jvmArgs);
            command.addAll(List.of("-cp", property("tracetail.classPath." + setup), main));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream printed = new ByteArrayOutputStream();
            Thread printer = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(printed);
                } catch (IOException ignored) {
                    // the application exited
                }
            });
            printer.start();

            List<Map<String, String>> lines = receive(server, process);
            boolean exited = process.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
            }
            printer.join();
            String console = printed.toString();
            assertTrue(exited, "the application did not exit; it printed:\n" + console);
            assertEquals(0, process.exitValue(), "the application failed; it printed:\n" + console);
            assertFalse(lines.isEmpty(), "the agent sent nothing; the application printed:\n" + console);
            return new AppRun(lines, console);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The first line whose message starts with the text; fails when there is none. */
    Map<String, String> line(String start) {
        return lines.stream()
            .filter(line -> line.getOrDefault("message", "").startsWith(start))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no line starts with \"" + start + "\"; the messages: " + messages()));
    }

    boolean has(String start) {
        return lines.stream().anyMatch(line -> line.getOrDefault("message", "").startsWith(start));
    }

    private String messages() {
        return lines.stream().map(line -> line.get("message")).collect(Collectors.joining(" | "));
    }

    /** Reads the agent's lines until the application exits and the agent closes the connection. */
    private static List<Map<String, String>> receive(ServerSocket server, Process process) throws IOException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        server.setSoTimeout(100);
        Socket socket = null;
        while (socket == null && System.currentTimeMillis() < deadline) {
            // A connection made before the application exited is still waiting to be accepted.
            boolean alive = process.isAlive();
            try {
                socket = server.accept();
            } catch (SocketTimeoutException e) {
                if (!alive) {
                    break;
                }
            }
        }
        List<Map<String, String>> lines = new ArrayList<>();
        if (socket == null) {
            return lines;
        }
        try (Socket connection = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), UTF_8))) {
            connection.setSoTimeout(TIMEOUT_MILLIS);
            for (String line; (line = in.readLine()) != null; ) {
                lines.add(fields(line));
            }
        }
        return lines;
    }

    static Map<String, String> fields(String json) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
            fields.put(e.getKey(), e.getValue().getAsString());
        }
        return fields;
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            throw new IllegalStateException(name + " is not set; run the tests through Gradle");
        }
        return value;
    }
}
