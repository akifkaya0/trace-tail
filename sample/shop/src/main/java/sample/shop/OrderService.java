package sample.shop;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.tracing.Http;
import sample.tracing.Queue;
import sample.tracing.Step;

/** Places an order: prices it, has the stock app reserve the goods, takes the payment and tells the stock app. */
final class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final AtomicInteger next = new AtomicInteger(1000);
    private final Catalog catalog = new Catalog();
    private final PaymentGateway payments = new PaymentGateway();
    private final AuditTrail audit = new AuditTrail();

    record Order(String id, String sku, int qty) {
    }

    /** Returns the order, or null when the stock app has too few of the goods. */
    Order place(String sku, int qty) {
        return Step.method(log).call(step -> {
            BigDecimal price = catalog.price(sku);
            BigDecimal amount = price.multiply(BigDecimal.valueOf(qty));
            log.atInfo()
                    .addKeyValue("event.action", "ORDER_PRICED")
                    .addKeyValue("sku", sku)
                    .addKeyValue("qty", qty)
                    .addKeyValue("price", price)
                    .addKeyValue("amount", amount)
                    .log("Order priced");

            Http.Reply reserved = Http.call("stock", "RESERVE", Shop.STOCK + "/reserve?sku=" + sku + "&qty=" + qty);
            if (reserved.status() == 409) {
                step.outcome("REJECTED");
                return null;
            }
            payments.charge(amount);

            Order order = new Order("o-" + next.incrementAndGet(), sku, qty);
            log.atInfo().addKeyValue("event.action", "ORDER_PLACED").addKeyValue("orderId", order.id()).log("Order placed");
            audit.record(order);
            Queue.publish(Shop.STOCK, "order.placed", Map.of("orderId", order.id(), "sku", sku));
            return order;
        });
    }
}
