package io.github.akifkaya0.tracetail.agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The sender in this JVM, writing to a socket that stands in for the plugin's: which lines it keeps
 * while the plugin does not read, and how it connects once the plugin listens.
 */
class SenderTest {

    private static final int CAPACITY = 10_000;
    private static final int TIMEOUT_MILLIS = 30_000;

    @Test
    void dropsTheOldestLinesWhileThePluginDoesNotRead() throws IOException {
        int count = 4 * CAPACITY;
        String padding = "x".repeat(1_000);
        try (ServerSocket server = listen(0)) {
            Sender sender = new Sender(server.getLocalPort());
            // Nothing reads yet: the socket's buffers fill, the sender waits on them, and its queue fills.
            for (int i = 1; i <= count; i++) {
                sender.send(i + " " + padding);
            }
            List<Integer> received = new ArrayList<>();
            server.setSoTimeout(TIMEOUT_MILLIS);
            try (Socket socket = server.accept(); BufferedReader in = reader(socket)) {
                while (received.isEmpty() || received.get(received.size() - 1) < count) {
                    String line = in.readLine();
                    assertNotNull(line, "the connection closed after " + received.size() + " lines");
                    received.add(Integer.valueOf(line.substring(0, line.indexOf(' '))));
                }
            }
            assertTrue(received.size() < count, "no line was dropped");
            for (int i = 1; i < received.size(); i++) {
                assertTrue(received.get(i - 1) < received.get(i), "line " + received.get(i) + " came after " + received.get(i - 1));
            }
            // In order and ending with the last, so the newest lines all arrived.
            assertEquals(count - CAPACITY + 1, received.get(received.size() - CAPACITY));
        }
    }

    @Test
    void connectsOnceThePluginListens() throws Exception {
        int port;
        try (ServerSocket probe = listen(0)) {
            port = probe.getLocalPort();
        }
        Sender sender = new Sender(port);
        // The connection is refused, as when the application starts before the plugin listens.
        sender.send("before");
        Thread.sleep(300);
        try (ServerSocket server = listen(port);
             Socket socket = acceptWhileSending(server, sender);
             BufferedReader in = reader(socket)) {
            assertNotNull(in.readLine());
        }
    }

    /** Sends a line now and then, as a running application logs, until the socket gets a connection. */
    private static Socket acceptWhileSending(ServerSocket server, Sender sender) throws IOException {
        server.setSoTimeout(100);
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        for (int i = 0; System.currentTimeMillis() < deadline; i++) {
            sender.send("line " + i);
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(TIMEOUT_MILLIS);
                return socket;
            } catch (SocketTimeoutException e) {
                // not yet
            }
        }
        throw new AssertionError("the sender did not connect");
    }

    private static ServerSocket listen(int port) throws IOException {
        return new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        socket.setSoTimeout(TIMEOUT_MILLIS);
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
    }
}
