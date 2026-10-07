package sample.tracing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * HTTP on the JDK's own server and client. The ids travel in headers: the trace id, the id of the
 * step that makes the call, and the user.
 */
public final class Http {

    public static final String USER_HEADER = "X-User-Id";
    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String PARENT_HEADER = "X-Parent-Id";

    private static final Logger log = LoggerFactory.getLogger(Http.class);
    private static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    private Http() {
    }

    /** What a handler answers. */
    public record Reply(int status, String body) {
    }

    public interface Handler {
        Reply handle(Map<String, String> query);
    }

    /** A server on the loopback address that serves each request on its own thread. */
    public static HttpServer server(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newCachedThreadPool(Threads.named("http")));
        return server;
    }

    /**
     * Serves [route], each request as an HTTP_IN step. A 4xx answer is a rejection. A failed call to
     * another system fails the request with a WARN line; any other exception with an ERROR line.
     */
    public static void serve(HttpServer server, String route, Handler handler) {
        server.createContext(route, exchange -> {
            Map<String, String> query = query(exchange);
            Reply reply = Step.request(log, "HTTP_IN", caller(exchange), "route", route).call(step -> {
                Reply r;
                try {
                    r = handler.handle(query);
                    if (r.status() >= 400) {
                        step.outcome("REJECTED");
                    }
                } catch (CallFailedException e) {
                    r = new Reply(e.status(), e.getMessage());
                    step.outcome("FAILURE").level(Level.WARN).field("downstream", e.target());
                } catch (RuntimeException e) {
                    r = new Reply(500, String.valueOf(e));
                    step.error(e);
                }
                step.field("status", r.status());
                return r;
            });
            send(exchange, reply);
        });
    }

    /**
     * Calls [url] as an HTTP_OUT step. A 4xx answer is a rejection and is returned. A 5xx answer, or
     * none, throws [CallFailedException].
     */
    public static Reply call(String target, String op, String url) {
        return Step.start(log, "HTTP_OUT", "target", target, "op", op).call(step -> {
            HttpResponse<String> response;
            try {
                response = post(url, step);
            } catch (IOException e) {
                step.outcome("FAILURE").level(Level.WARN).field("exception", e.getClass().getSimpleName());
                throw new CallFailedException(target, 502, target + " is not reachable");
            }
            int status = response.statusCode();
            step.field("status", status);
            if (status >= 500) {
                step.outcome("FAILURE").level(Level.WARN);
                throw new CallFailedException(target, 502, target + " answered " + status);
            }
            if (status >= 400) {
                step.outcome("REJECTED");
            }
            return new Reply(status, response.body());
        });
    }

    /** Posts to [url] with the ids of the current request and of [step], the one making the call. */
    static HttpResponse<String> post(String url, Step step) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header(TRACE_HEADER, Step.traceId())
                .header(PARENT_HEADER, step.id())
                .POST(HttpRequest.BodyPublishers.noBody());
        if (Step.userId() != null) {
            request.header(USER_HEADER, Step.userId());
        }
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    static Caller caller(HttpExchange exchange) {
        return new Caller(
                exchange.getRequestHeaders().getFirst(TRACE_HEADER),
                exchange.getRequestHeaders().getFirst(PARENT_HEADER),
                exchange.getRequestHeaders().getFirst(USER_HEADER));
    }

    static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                }
            }
        }
        return query;
    }

    static void send(HttpExchange exchange, Reply reply) throws IOException {
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
