package sample.stock;

import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.message.StringMapMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.tracing.Step;
import sample.tracing.Work;

/** The stock levels. A reservation only checks them; the levels stay as they are. */
final class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    /** Log4j2's own API, for a map message: its entries become fields of the line. */
    private static final org.apache.logging.log4j.Logger log4j = LogManager.getLogger(StockService.class);

    private static final Map<String, Integer> LEVELS = Map.of("chair", 500, "table", 5, "lamp", 40, "sofa", 12, "desk", 30);

    private final Warehouse warehouse = new Warehouse();

    boolean reserve(String sku, int qty) {
        return Step.method(log).call(step -> {
            String shelf = warehouse.locate(sku);
            int left = LEVELS.getOrDefault(sku, 0);
            if (left < qty) {
                step.outcome("REJECTED").field("reason", "OUT_OF_STOCK");
                return false;
            }
            log.info("Reserved {} {} from shelf {}", qty, sku, shelf);
            return true;
        });
    }

    String report() {
        return Step.method(log).call(step -> {
            Work.take(8000, 8000);
            return LEVELS.toString();
        });
    }

    void recount() {
        Step.method(log).run(step -> {
            Work.take(200, 400);
            LEVELS.forEach((sku, left) -> {
                if (left < 10) {
                    log4j.warn(new StringMapMessage()
                            .with("message", "Stock is low")
                            .with("event.action", "STOCK_LOW")
                            .with("sku", sku)
                            .with("left", left));
                }
            });
            log.atInfo().addKeyValue("event.action", "STOCK_RECOUNTED").addKeyValue("items", LEVELS.size()).log("Stock recounted");
        });
    }
}
