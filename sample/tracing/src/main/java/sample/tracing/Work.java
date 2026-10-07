package sample.tracing;

import java.util.concurrent.ThreadLocalRandom;

/** Stands in for real work. */
public final class Work {

    private Work() {
    }

    /** Takes between [minMs] and [maxMs] milliseconds. */
    public static void take(long minMs, long maxMs) {
        try {
            Thread.sleep(minMs >= maxMs ? minMs : ThreadLocalRandom.current().nextLong(minMs, maxMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
