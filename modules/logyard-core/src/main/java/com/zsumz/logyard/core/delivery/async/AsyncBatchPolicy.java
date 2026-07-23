package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.spi.output.BatchEventSink;

import java.time.Duration;
import java.util.Objects;

/** Validated batching policy advertised by an asynchronous delegate. */
record AsyncBatchPolicy(BatchEventSink delegate, int maximumSize, long maximumDelayNanos) {
    static AsyncBatchPolicy from(AsyncDelegateDelivery delivery) {
        BatchEventSink delegate = delivery.batchDelegate();
        int maximumSize = delegate == null ? 1 : delegate.maximumBatchSize();
        if (maximumSize < 1 || maximumSize > 4_096) {
            throw new IllegalArgumentException("batch size must be between 1 and 4096");
        }
        Duration maximumDelay = delegate == null ? Duration.ZERO : delegate.maximumBatchDelay();
        Objects.requireNonNull(maximumDelay, "maximumBatchDelay");
        if (maximumDelay.isNegative() || maximumDelay.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("batch delay must be between 0s and 1m");
        }
        return new AsyncBatchPolicy(delegate, maximumSize, saturatedNanos(maximumDelay));
    }

    boolean enabled() {
        return delegate != null;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
