package sample.shop;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.tracing.Http;
import sample.tracing.Http.Reply;

/** The shop app: takes its users' orders and has the stock app reserve the goods. Logs through Logback. */
public final class Shop {

    static final int PORT = 18080;
    static final String STOCK = "http://127.0.0.1:18081";

    private static final Logger log = LoggerFactory.getLogger(Shop.class);

    public static void main(String[] args) throws IOException {
        OrderService orders = new OrderService();
        HttpServer server = Http.server(PORT);
        Http.serve(server, "/orders", query -> {
            OrderService.Order order = orders.place(query.get("sku"), Integer.parseInt(query.getOrDefault("qty", "1")));
            return order != null ? new Reply(200, order.id()) : new Reply(409, "Out of stock");
        });
        Http.serve(server, "/report", query -> Http.call("stock", "REPORT", STOCK + "/report"));
        server.start();
        log.info("Listening on http://127.0.0.1:{}", PORT);
        new Customer("http://127.0.0.1:" + PORT).start();
    }
}
