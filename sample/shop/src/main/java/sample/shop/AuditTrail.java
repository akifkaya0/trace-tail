package sample.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sample.tracing.Step;
import sample.tracing.Work;

/** Keeps a record of the placed orders. */
final class AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);

    void record(OrderService.Order order) {
        Step.method(log).run(step -> {
            Work.take(10, 40);
            log.atDebug().addKeyValue("orderId", order.id()).log("Audit entry written");
            // A desk's step never writes its END line, as if the process died in the middle of it.
            if (order.sku().equals("desk")) {
                step.abandon();
            }
        });
    }
}
