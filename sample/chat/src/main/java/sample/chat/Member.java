package sample.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import sample.tracing.Threads;

/**
 * The app's members, sending STOMP frames. Every two seconds it sends the next scenario's frames, and it
 * takes commands typed into the Run console. It writes no log lines, so the view shows its frames as
 * coming from outside.
 */
final class Member {

    private record Frame(String command, String destination, Map<String, String> body) {
    }

    private static final Map<String, List<Frame>> SCENARIOS = new LinkedHashMap<>();

    static {
        SCENARIOS.put("connect", List.of(new Frame("CONNECT", null, Map.of())));
        SCENARIOS.put("subscribe", List.of(
                new Frame("SUBSCRIBE", "/topic/general", Map.of()),
                new Frame("SEND", "/app/history", Map.of("room", "general"))));
        SCENARIOS.put("invite", List.of(new Frame("SEND", "/app/invite", Map.of("invitee", "u-1003"))));
        SCENARIOS.put("heartbeat", List.of(new Frame("HEARTBEAT", null, Map.of())));
        SCENARIOS.put("error", List.of(new Frame("SEND", "/app/history", Map.of("room", "archive"))));
        SCENARIOS.put("flood", Collections.nCopies(8, new Frame("SEND", "/app/history", Map.of("room", "random"))));
        SCENARIOS.put("disconnect", List.of(
                new Frame("UNSUBSCRIBE", "/topic/general", Map.of()),
                new Frame("DISCONNECT", null, Map.of())));
    }

    private static final String[] USERS = {"u-1001", "u-1002", "u-1003"};

    private final Stomp stomp;
    private final ScheduledExecutorService socket = Executors.newSingleThreadScheduledExecutor(Threads.named("ws"));
    private final List<String> rotation = List.copyOf(SCENARIOS.keySet());
    private int next;
    private volatile boolean stopped;

    Member(Stomp stomp) {
        this.stomp = stomp;
    }

    void start() {
        System.out.println("Sending frames every 2 seconds. Type stop, go, or a scenario: "
                + String.join(", ", SCENARIOS.keySet()));
        socket.scheduleWithFixedDelay(() -> {
            if (!stopped) {
                send(rotation.get(next++ % rotation.size()));
            }
        }, 3, 2, TimeUnit.SECONDS);
        Thread console = new Thread(this::readCommands, "console");
        console.setDaemon(true);
        console.start();
    }

    private void readCommands() {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        try {
            for (String line; (line = in.readLine()) != null; ) {
                String command = line.trim();
                switch (command) {
                    case "" -> { }
                    case "stop" -> stopped = true;
                    case "go" -> stopped = false;
                    default -> {
                        if (SCENARIOS.containsKey(command)) {
                            socket.execute(() -> send(command));
                        } else {
                            System.out.println("Unknown command: " + line);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("The console stopped taking commands: " + e);
        }
    }

    /** Sends the scenario's frames from one user, on the socket's thread, as a WebSocket session would. */
    private void send(String scenario) {
        String user = USERS[ThreadLocalRandom.current().nextInt(USERS.length)];
        for (Frame frame : SCENARIOS.get(scenario)) {
            stomp.receive(user, frame.command(), frame.destination(), frame.body());
        }
    }
}
