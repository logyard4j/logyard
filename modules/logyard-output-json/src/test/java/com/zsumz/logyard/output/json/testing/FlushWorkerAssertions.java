package com.zsumz.logyard.output.json.testing;

import java.util.concurrent.TimeUnit;

/** Assertions for retirement of shared temporary JSON flush workers. */
public final class FlushWorkerAssertions {
    private static final String WORKER_NAME_PREFIX = "logyard-json-flush-worker-";

    private FlushWorkerAssertions() {
    }

    public static void awaitNoFlushWorkers() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (liveFlushWorkers() != 0L) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("JSON flush workers did not retire after all sinks closed");
            }
            Thread.onSpinWait();
        }
    }

    private static long liveFlushWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith(WORKER_NAME_PREFIX))
                .count();
    }
}
