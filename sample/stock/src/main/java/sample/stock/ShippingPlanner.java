package sample.stock;

import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.tracing.Step;
import sample.tracing.Work;

/** Plans the shipment of a placed order, when the order's message arrives. */
final class ShippingPlanner {

    private static final Logger log = LoggerFactory.getLogger(ShippingPlanner.class);

    void plan(String orderId, String sku) {
        Step.method(log).run(step -> {
            Work.take(300, 900);
            log.atInfo()
                    .addKeyValue("event.action", "SHIPMENT_PLANNED")
                    .addKeyValue("orderId", orderId)
                    .addKeyValue("sku", sku)
                    .addKeyValue("days", ThreadLocalRandom.current().nextInt(1, 5))
                    .log("Shipment planned");
        });
    }
}
