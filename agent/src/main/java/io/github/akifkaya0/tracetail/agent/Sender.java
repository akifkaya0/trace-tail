package io.github.akifkaya0.tracetail.agent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Writes the lines to the plugin's port on its own thread, so the application's logging never
 * waits on the IDE. A full queue drops its oldest line. While the IDE does not listen, the lines
 * are dropped and the connection is tried again every few seconds.
 */
final class Sender {

    private static final int CAPACITY = 10_000;
    private static final long RETRY_MILLIS = 2_000;
    private static final long DRAIN_MILLIS = 1_000;

    private final int port;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<String>(CAPACITY);
    private volatile boolean idle = true;
    private volatile Socket socket;

    Sender(int port) {
        this.port = port;
        Thread thread = new Thread(this::run, "Trace Tail sender");
        thread.setDaemon(true);
        thread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::drain, "Trace Tail drain"));
    }

    void send(String line) {
        while (!queue.offer(line)) {
            queue.poll();
        }
    }

    private void run() {
        Writer out = null;
        long retryAt = 0;
        while (true) {
            try {
                String line = queue.poll();
                if (line == null) {
                    if (out != null) {
                        out.flush();
                    }
                    idle = true;
                    line = queue.take();
                    idle = false;
                }
                if (out == null) {
                    if (System.currentTimeMillis() < retryAt) {
                        continue;
                    }
                    try {
                        socket = new Socket(InetAddress.getLoopbackAddress(), port);
                        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), 1 << 16);
                    } catch (IOException e) {
                        retryAt = System.currentTimeMillis() + RETRY_MILLIS;
                        continue;
                    }
                }
                out.write(line);
                out.write('\n');
            } catch (IOException e) {
                // The IDE closed the connection.
                closeQuietly(socket);
                socket = null;
                out = null;
                retryAt = System.currentTimeMillis() + RETRY_MILLIS;
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * At exit, gives the last lines, such as a crash's stack trace, a moment to leave, then closes
     * the connection. Without the close, Windows resets it, and the IDE loses the lines it has not
     * read yet.
     */
    private void drain() {
        long end = System.currentTimeMillis() + DRAIN_MILLIS;
        while (!(idle && queue.isEmpty()) && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
