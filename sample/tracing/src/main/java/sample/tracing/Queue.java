package sample.tracing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * A stand-in for a message broker. Publishing posts the message to the consuming app, which answers
 * at once and handles the message later on a worker thread.
 */
public final class Queue {

    private static final Logger log = LoggerFactory.getLogger(Queue.class);
    private static final ExecutorService workers = Executors.newFixedThreadPool(2, Threads.named("mq-worker"));

    private Queue() {
    }

    /** Publishes [message] to [queue] in the app at [baseUrl], as an MQ_OUT step. */
    public static void publish(String baseUrl, String queue, Map<String, String> message) {
        String url = baseUrl + "/queue/" + queue + "?" + message.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        Step.start(log, "MQ_OUT", "queue", queue).run(step -> {
            try {
                Http.post(url, step);
            } catch (IOException e) {
                step.outcome("FAILURE").level(Level.WARN).field("exception", e.getClass().getSimpleName());
                throw new CallFailedException("queue", 502, "The message could not be published");
            }
        });
    }

    /** Takes the messages of [queue], each as an MQ_IN step on a worker thread. */
    public static void consume(HttpServer server, String queue, Consumer<Map<String, String>> handler) {
        server.createContext("/queue/" + queue, exchange -> {
            Caller caller = Http.caller(exchange);
            Map<String, String> message = Http.query(exchange);
            Http.send(exchange, new Http.Reply(202, "queued"));
            workers.execute(() -> Step.request(log, "MQ_IN", caller, "queue", queue).run(step -> handler.accept(message)));
        });
    }
}
