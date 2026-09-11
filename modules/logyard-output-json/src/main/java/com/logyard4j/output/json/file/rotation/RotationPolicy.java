package com.logyard4j.output.json.file.rotation;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable file-lifecycle policy independent of the configuration parser.
 *
 * <p>{@code maximumBytes} always applies. {@code maximumAge} is optional and, when present, caps how
 * long one active data file may stay open; it is an <em>age</em> measured from the moment this
 * process opened or created that file and is never aligned to a calendar boundary, so a one-hour
 * policy rotates one hour after the file was opened rather than on the hour. Both limits are checked
 * at the same record boundary and whichever is reached first rotates the file.</p>
 */
public record RotationPolicy(
        long maximumBytes,
        int retainedArchives,
        Compression compression,
        Duration maintenanceShutdownTimeout,
        Duration maximumAge) {
    public static final long MINIMUM_BYTES = 1_024L;
    public static final long MAXIMUM_BYTES = 1L << 50;
    public static final int MAXIMUM_ARCHIVES = 10_000;
    /**
     * Smallest age the configuration surface accepts.
     *
     * <p>The policy itself admits any positive age so that the mechanism can be exercised directly;
     * the configuration decoder is what holds callers to this floor.</p>
     */
    public static final Duration MINIMUM_CONFIGURED_AGE = Duration.ofSeconds(1);
    public static final Duration MAXIMUM_AGE = Duration.ofDays(365);

    public RotationPolicy {
        if (maximumBytes < MINIMUM_BYTES || maximumBytes > MAXIMUM_BYTES) {
            throw new IllegalArgumentException(
                    "rotation size must be between " + MINIMUM_BYTES + " and " + MAXIMUM_BYTES + " bytes");
        }
        if (retainedArchives < 1 || retainedArchives > MAXIMUM_ARCHIVES) {
            throw new IllegalArgumentException(
                    "retained archive count must be between 1 and " + MAXIMUM_ARCHIVES);
        }
        Objects.requireNonNull(compression, "compression");
        Objects.requireNonNull(maintenanceShutdownTimeout, "maintenanceShutdownTimeout");
        if (maintenanceShutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("maintenance shutdown timeout must not be negative");
        }
        if (maximumAge != null
                && (maximumAge.isNegative() || maximumAge.isZero() || maximumAge.compareTo(MAXIMUM_AGE) > 0)) {
            throw new IllegalArgumentException("rotation interval must be positive and at most " + MAXIMUM_AGE);
        }
    }

    /** Size-only policy that leaves the active file's age unbounded. */
    public RotationPolicy(
            long maximumBytes,
            int retainedArchives,
            Compression compression,
            Duration maintenanceShutdownTimeout) {
        this(maximumBytes, retainedArchives, compression, maintenanceShutdownTimeout, null);
    }

    public static RotationPolicy of(long maximumBytes, int retainedArchives, String compression) {
        return new RotationPolicy(
                maximumBytes,
                retainedArchives,
                Compression.parse(compression),
                Duration.ofSeconds(5));
    }

    /** Archive compression mode. */
    public enum Compression {
        NONE,
        GZIP;

        public static Compression parse(String value) {
            Objects.requireNonNull(value, "value");
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "none" -> NONE;
                case "gzip" -> GZIP;
                default -> throw new IllegalArgumentException("compression must be none or gzip");
            };
        }
    }
}
