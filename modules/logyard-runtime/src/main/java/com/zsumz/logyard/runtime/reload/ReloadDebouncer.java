package com.zsumz.logyard.runtime.reload;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class ReloadDebouncer {
    private static final Duration MINIMUM_TRANSIENT_RETRY = Duration.ofMillis(100L);
    private static final Duration MAXIMUM_TRANSIENT_RETRY = Duration.ofSeconds(5L);

    private final long delayNanos;
    private final long minimumTransientRetryNanos;
    private final long maximumTransientRetryNanos;
    private final LongSupplier nanoTime;
    private long deadline = Long.MAX_VALUE;
    private int transientRetryExponent;

    ReloadDebouncer(Duration delay) {
        this(delay, System::nanoTime);
    }

    ReloadDebouncer(Duration delay, LongSupplier nanoTime) {
        this(delay, MINIMUM_TRANSIENT_RETRY, MAXIMUM_TRANSIENT_RETRY, nanoTime);
    }

    ReloadDebouncer(Duration delay, Duration minimumTransientRetry, Duration maximumTransientRetry, LongSupplier nanoTime) {
        delayNanos = saturatedNanos(Objects.requireNonNull(delay, "delay"));
        minimumTransientRetryNanos = saturatedNanos(Objects.requireNonNull(minimumTransientRetry, "minimumTransientRetry"));
        maximumTransientRetryNanos = saturatedNanos(Objects.requireNonNull(maximumTransientRetry, "maximumTransientRetry"));
        if (minimumTransientRetryNanos > maximumTransientRetryNanos) {
            throw new IllegalArgumentException("minimum transient retry must not exceed maximum transient retry");
        }
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    void signalChange() {
        transientRetryExponent = 0;
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
            } else if (outcome == WatcherReloadOutcome.TRANSIENT_RETRY) {
                schedule(transientRetryDelay());
            } else {
                transientRetryExponent = 0;
            }
        }
    }

    private long transientRetryDelay() {
        long base = Math.max(delayNanos, minimumTransientRetryNanos);
        int exponent = transientRetryExponent;
        if (transientRetryExponent < 62) {
            transientRetryExponent++;
        }
        long multiplier = 1L << exponent;
        return Math.min(maximumTransientRetryNanos, saturatedMultiply(base, multiplier));
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
