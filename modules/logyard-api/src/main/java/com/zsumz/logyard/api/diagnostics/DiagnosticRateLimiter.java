package com.zsumz.logyard.api.diagnostics;

import com.zsumz.logyard.api.annotation.InternalApi;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Non-blocking report permit that counts diagnostics suppressed during its interval. */
@InternalApi
public final class DiagnosticRateLimiter {
    private final long intervalNanos;
    private final AtomicLong nextAllowedNanos = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();

    /**
     * Creates a diagnostic report limiter.
     *
     * @param interval minimum time between granted report permits
     * @throws IllegalArgumentException when the interval is negative
     */
    public DiagnosticRateLimiter(Duration interval) {
        Duration configured = Objects.requireNonNull(interval, "interval");
        if (configured.isNegative()) {
            throw new IllegalArgumentException("diagnostic report interval must not be negative");
        }
        intervalNanos = saturatedNanos(configured);
    }

    /**
     * Acquires the next report window, or counts this attempt as suppressed.
     *
     * @return whether this attempt acquired a report permit
     */
    public boolean tryAcquire() {
        long now = System.nanoTime();
        while (true) {
            long next = nextAllowedNanos.get();
            if (now < next) {
                suppressed.incrementAndGet();
                return false;
            }
            if (nextAllowedNanos.compareAndSet(next, saturatedAdd(now, intervalNanos))) {
                return true;
            }
        }
    }

    /**
     * Returns and clears the number of suppressed reports observed since the last drain.
     *
     * @return the suppressed report count before clearing
     */
    public long drainSuppressed() {
        return suppressed.getAndSet(0L);
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        return result < left ? Long.MAX_VALUE : result;
    }
}
