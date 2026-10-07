package sample.shop;

import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The prices, kept in memory. */
final class Catalog {

    private static final Logger log = LoggerFactory.getLogger(Catalog.class);

    private static final Map<String, BigDecimal> PRICES = Map.of(
            "chair", new BigDecimal("49.90"),
            "table", new BigDecimal("219.00"),
            "lamp", new BigDecimal("34.50"),
            "sofa", new BigDecimal("899.00"),
            "desk", new BigDecimal("189.00"));

    BigDecimal price(String sku) {
        BigDecimal price = PRICES.get(sku);
        log.atDebug().addKeyValue("sku", sku).addKeyValue("price", price).log("Price read from the catalog");
        return price;
    }
}
