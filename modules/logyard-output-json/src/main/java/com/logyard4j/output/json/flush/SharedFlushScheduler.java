package com.logyard4j.output.json.flush;

import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Lazily started, process-wide daemon scheduler for JSON output flush deadlines. */
final class SharedFlushScheduler implements FlushScheduler {
    static final SharedFlushScheduler INSTANCE = new SharedFlushScheduler();

    private final ScheduledThreadPoolExecutor executor;

    private SharedFlushScheduler() {
        executor = new ScheduledThreadPoolExecutor(2, new FlushThreadFactory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public ScheduledFlush schedule(Duration delay, Runnable action) {
        var future = executor.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    private static final class FlushThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(
                    null,
                    task,
                    "logyard-json-flush-deadline-" + sequence.incrementAndGet(),
                    0L,
                    false);
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        }
    }
}
