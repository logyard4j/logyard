package com.zsumz.logyard.output.json.file.rotation;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Immutable file-lifecycle policy independent of the configuration parser. */
public record RotationPolicy(
        long maximumBytes,
        int retainedArchives,
        Compression compression,
        Duration maintenanceShutdownTimeout) {
    public static final long MINIMUM_BYTES = 1_024L;
    public static final long MAXIMUM_BYTES = 1L << 50;
    public static final int MAXIMUM_ARCHIVES = 10_000;

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
