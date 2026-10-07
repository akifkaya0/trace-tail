package sample.tracing;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class Threads {

    private Threads() {
    }

    /** Names the threads [prefix]-1, [prefix]-2, …, which the lines show as their thread. */
    public static ThreadFactory named(String prefix) {
        AtomicInteger next = new AtomicInteger();
        return task -> new Thread(task, prefix + "-" + next.incrementAndGet());
    }
}
