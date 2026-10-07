package sample.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.tracing.Step;

/** Knows the shelf of each item. The lamp has none, so reserving it fails with an exception. */
final class Warehouse {

    private static final Logger log = LoggerFactory.getLogger(Warehouse.class);

    String locate(String sku) {
        return Step.method(log).call(step -> shelf(sku));
    }

    private String shelf(String sku) {
        if (sku.equals("lamp")) {
            throw new IllegalStateException("No shelf is assigned to " + sku);
        }
        return "A-" + Math.floorMod(sku.hashCode(), 40);
    }
}
