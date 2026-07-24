package com.zsumz.logyard.runtime.reload;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class ReloadDebouncer {
    private static final int MAX_TRANSIENT_RETRIES = 3;

    private final long delayNanos;
    private final LongSupplier nanoTime;
    private long deadline = Long.MAX_VALUE;
    private int transientRetries;

    ReloadDebouncer(Duration delay) {
        this(delay, System::nanoTime);
    }

    ReloadDebouncer(Duration delay, LongSupplier nanoTime) {
        delayNanos = saturatedNanos(Objects.requireNonNull(delay, "delay"));
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    void signalChange() {
        transientRetries = 0;
        schedule(delayNanos);
    }

    private void schedule(long delay) {
        long now = nanoTime.getAsLong();
        long candidate = now + delay;
        deadline = candidate < 0L && now > 0L ? Long.MAX_VALUE - 1L : candidate;
    }

    void runIfDue(Supplier<WatcherReloadOutcome> reload) {
        Objects.requireNonNull(reload, "reload");
        if (deadline != Long.MAX_VALUE && nanoTime.getAsLong() - deadline >= 0L) {
            deadline = Long.MAX_VALUE;
            WatcherReloadOutcome outcome = Objects.requireNonNull(reload.get(), "reload result");
            if (outcome == WatcherReloadOutcome.BUSY_RETRY) {
                schedule(delayNanos);
            } else if (outcome == WatcherReloadOutcome.TRANSIENT_RETRY && transientRetries < MAX_TRANSIENT_RETRIES) {
                transientRetries++;
                schedule(saturatedMultiply(delayNanos, 1L << transientRetries));
            }
        }
    }

    private static long saturatedMultiply(long value, long multiplier) {
        if (value == 0L || multiplier == 0L) {
            return 0L;
        }
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
