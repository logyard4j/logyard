package com.logyard4j.logyard.output.json.flush;

import java.time.Duration;
import java.util.Objects;

/** Validation policy for the bounded timed-flush interval. */
final class TimedFlushInterval {
    static final Duration MAXIMUM = Duration.ofMinutes(1L);

    private TimedFlushInterval() {
    }

    static Duration requireValid(Duration interval) {
        Objects.requireNonNull(interval, "flushInterval");
        if (interval.isNegative() || interval.compareTo(MAXIMUM) > 0) {
            throw new IllegalArgumentException("flush interval must be between 0s and 1m");
        }
        return interval;
    }
}
