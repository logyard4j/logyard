package com.zsumz.logyard.output.json.file;

import com.zsumz.logyard.output.json.file.rotation.RotationPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Elapsed time since open, with time since the last write inherited when appending.
 * Wall-clock adjustments after open do not affect the deadline. Last-modified time
 * is an approximation across restarts; frequent restarts of a busy file can extend
 * its total lifetime beyond the interval. Size rotation remains independent.
 */
final class ActiveFileAge {
    private final LongSupplier clock;
    private long openedNanos;
    private long inheritedNanos;

    ActiveFileAge(LongSupplier clock) {
        this.clock = clock;
        openedNanos = clock.getAsLong();
    }

    /** Restarts the clock for a freshly attached data file that already carries {@code inherited} age. */
    void opened(long inheritedNanos) {
        this.inheritedNanos = Math.max(inheritedNanos, 0L);
        openedNanos = clock.getAsLong();
    }

    boolean exceeds(Duration maximum) {
        return maximum != null && elapsedNanos() >= maximum.toNanos();
    }

    /** Age the file at {@code path} already carries, or zero when it is not being resumed. */
    static long inheritedNanos(Path path, boolean append) {
        if (!append) {
            return 0L;
        }
        try {
            long modifiedMillis = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
            long agedMillis = Math.max(System.currentTimeMillis() - modifiedMillis, 0L);
            return Math.min(agedMillis, RotationPolicy.MAXIMUM_AGE.toMillis()) * 1_000_000L;
        } catch (IOException | RuntimeException unreadable) {
            return 0L;
        }
    }

    private long elapsedNanos() {
        return inheritedNanos + Math.max(clock.getAsLong() - openedNanos, 0L);
    }
}
