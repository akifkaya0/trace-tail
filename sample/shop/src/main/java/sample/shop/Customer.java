package sample.shop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import sample.tracing.Http;
import sample.tracing.Threads;

/**
 * The shop's users. Every two seconds it sends the next scenario's request, and it takes commands
 * typed into the Run console. It writes no log lines, so the view shows its requests as coming from
 * outside.
 */
final class Customer {

    private static final Map<String, String> SCENARIOS = new LinkedHashMap<>();

    static {
        SCENARIOS.put("order", "/orders?sku=chair&qty=2");
        SCENARIOS.put("rejected", "/orders?sku=table&qty=50");
        SCENARIOS.put("error", "/orders?sku=lamp&qty=1");
        SCENARIOS.put("timeout", "/orders?sku=sofa&qty=1");
        SCENARIOS.put("unfinished", "/orders?sku=desk&qty=1");
        SCENARIOS.put("slow", "/report");
    }

    private static final String[] USERS = {"u-1001", "u-1002", "u-1003"};

    private final String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> rotation = List.copyOf(SCENARIOS.keySet());
    private int next;
    private volatile boolean stopped;

    Customer(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    void start() {
        System.out.println("Sending a request every 2 seconds. Type stop, go, burst <count>, or a scenario: "
                + String.join(", ", SCENARIOS.keySet()));
        Executors.newSingleThreadScheduledExecutor(Threads.named("customer")).scheduleWithFixedDelay(() -> {
            if (!stopped) {
                request(rotation.get(next++ % rotation.size()));
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
                String[] words = line.trim().split("\\s+");
                switch (words[0]) {
                    case "" -> { }
                    case "stop" -> stopped = true;
                    case "go" -> stopped = false;
                    case "burst" -> {
                        int count = words.length > 1 ? Integer.parseInt(words[1]) : 1000;
                        new Thread(() -> burst(count), "burst").start();
                    }
                    default -> {
                        if (SCENARIOS.containsKey(words[0])) {
                            request(words[0]);
                        } else {
                            System.out.println("Unknown command: " + line);
                        }
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.out.println("The console stopped taking commands: " + e);
        }
    }

    /** Sends [count] orders, at most 20 at a time, to show how the view keeps up and what it drops. */
    private void burst(int count) {
        Semaphore inFlight = new Semaphore(20);
        for (int i = 0; i < count; i++) {
            inFlight.acquireUninterruptibly();
            request("order").whenComplete((response, error) -> inFlight.release());
        }
        inFlight.acquireUninterruptibly(20);
        System.out.println("Sent " + count + " orders");
    }

    private CompletableFuture<HttpResponse<Void>> request(String scenario) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + SCENARIOS.get(scenario)))
                .header(Http.USER_HEADER, USERS[ThreadLocalRandom.current().nextInt(USERS.length)])
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }
}
