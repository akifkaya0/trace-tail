package sample.chat;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.tracing.Caller;
import sample.tracing.Step;
import sample.tracing.Work;

/**
 * A stand-in for a STOMP endpoint over WebSocket. Each frame a user sends is a WS_IN step and each
 * frame sent to them a WS_OUT step. A user's frames over three a second are rejected, and a frame
 * whose handling fails is answered with an ERROR frame. It keeps who subscribed to which destination.
 */
final class Stomp {

    private static final Logger log = LoggerFactory.getLogger(Stomp.class);
    private static final int FRAMES_PER_SECOND = 3;

    private final Map<String, BiConsumer<String, Map<String, String>>> handlers = new ConcurrentHashMap<>();
    private final Map<String, long[]> frames = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    /** Handles the frames sent to [key], a destination such as /app/alert, or a command such as CONNECT. */
    void on(String key, BiConsumer<String, Map<String, String>> handler) {
        handlers.put(key, handler);
    }

    /** Takes a frame from [user]. The connection frames and heartbeats have no [destination]. */
    void receive(String user, String command, String destination, Map<String, String> body) {
        Step.request(log, "WS_IN", new Caller(null, null, user), fields(command, destination)).run(step -> {
            if (!allow(user)) {
                step.outcome("REJECTED");
                return;
            }
            switch (command) {
                case "SUBSCRIBE" -> subscribers(destination).add(user);
                case "UNSUBSCRIBE" -> subscribers(destination).remove(user);
                case "DISCONNECT" -> subscriptions.values().forEach(users -> users.remove(user));
                default -> { }
            }
            BiConsumer<String, Map<String, String>> handler = handlers.get(destination != null ? destination : command);
            if (handler == null) {
                return;
            }
            try {
                handler.accept(user, body);
            } catch (RuntimeException e) {
                step.error(e).outcome("ERROR");
                send("ERROR", null);
            }
        });
    }

    /** Sends a frame to the user of the frame being handled. */
    void send(String command, String destination) {
        Step.start(log, "WS_OUT", fields(command, destination)).run(step -> Work.take(1, 5));
    }

    /** Sends a frame to each user subscribed to [destination], each a request of its own. */
    void broadcast(String command, String destination) {
        for (String user : subscribers(destination)) {
            Step.request(log, "WS_OUT", new Caller(null, null, user), fields(command, destination)).run(step -> Work.take(1, 5));
        }
    }

    private Set<String> subscribers(String destination) {
        return subscriptions.computeIfAbsent(destination, d -> ConcurrentHashMap.newKeySet());
    }

    private boolean allow(String user) {
        long second = System.currentTimeMillis() / 1000;
        long[] count = frames.merge(user, new long[] {second, 1},
                (old, first) -> old[0] == second ? new long[] {second, old[1] + 1} : first);
        return count[1] <= FRAMES_PER_SECOND;
    }

    private static Object[] fields(String command, String destination) {
        return destination == null
                ? new Object[] {"command", command}
                : new Object[] {"command", command, "destination", destination};
    }
}
