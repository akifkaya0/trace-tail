package sample.shop;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import sample.tracing.CallFailedException;
import sample.tracing.Step;
import sample.tracing.Work;

/** A stand-in for an outside payment system, which writes no lines here. Amounts over 500 time out. */
final class PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PaymentGateway.class);
    private static final BigDecimal TIMES_OUT_OVER = new BigDecimal("500");

    void charge(BigDecimal amount) {
        Step.start(log, "HTTP_OUT", "target", "payment-gateway", "op", "CHARGE").run(step -> {
            if (amount.compareTo(TIMES_OUT_OVER) > 0) {
                Work.take(2000, 2000);
                step.outcome("TIMEOUT").level(Level.WARN);
                throw new CallFailedException("payment-gateway", 504, "The payment gateway did not answer");
            }
            Work.take(100, 300);
            step.field("status", 200);
        });
    }
}
