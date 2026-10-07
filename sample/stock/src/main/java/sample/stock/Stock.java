package sample.stock;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.tracing.Caller;
import sample.tracing.Http;
import sample.tracing.Http.Reply;
import sample.tracing.Queue;
import sample.tracing.Step;
import sample.tracing.Threads;

/**
 * The stock app: reserves goods, plans the shipment of placed orders and recounts the stock every 20
 * seconds. Logs through Log4j2, and keeps the ids under Micrometer's names.
 */
public final class Stock {

    static final int PORT = 18081;

    private static final Logger log = LoggerFactory.getLogger(Stock.class);

    public static void main(String[] args) throws IOException {
        Step.useMicrometerNames();
        StockService stock = new StockService();
        ShippingPlanner shipping = new ShippingPlanner();

        HttpServer server = Http.server(PORT);
        Http.serve(server, "/reserve", query -> stock.reserve(query.get("sku"), Integer.parseInt(query.get("qty")))
                ? new Reply(200, "Reserved")
                : new Reply(409, "Out of stock"));
        Http.serve(server, "/report", query -> new Reply(200, stock.report()));
        Queue.consume(server, "order.placed", message -> shipping.plan(message.get("orderId"), message.get("sku")));
        server.start();
        log.info("Listening on http://127.0.0.1:{}", PORT);

        Executors.newSingleThreadScheduledExecutor(Threads.named("scheduler")).scheduleAtFixedRate(
                () -> Step.request(log, "JOB", Caller.NONE, "job", "recount").run(step -> stock.recount()),
                5, 20, TimeUnit.SECONDS);
    }
}
