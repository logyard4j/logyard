package com.logyard4j.output.json.flush;

import com.logyard4j.api.annotation.InternalApi;

import java.time.Duration;

/** Internal scheduling seam for one-shot JSON output flushes. */
@InternalApi
public interface FlushScheduler {
    static FlushScheduler shared() {
        return SharedFlushScheduler.INSTANCE;
    }

    ScheduledFlush schedule(Duration delay, Runnable action);

    /** Cancellation handle for one scheduled flush. */
    @FunctionalInterface
    interface ScheduledFlush {
        void cancel();
    }
}
