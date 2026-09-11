package com.logyard4j.api;

import java.util.Locale;

/** Severity ordered from least to most severe. */
public enum Level {
    /** Highly detailed diagnostic events. */
    TRACE(1),

    /** Diagnostic events useful during development and investigation. */
    DEBUG(5),

    /** Normal operational events. */
    INFO(9),

    /** Potential problems from which the application can continue. */
    WARN(13),

    /** Failures requiring operator or application attention. */
    ERROR(17);

    private final int severityNumber;

    Level(int severityNumber) {
        this.severityNumber = severityNumber;
    }

    /**
     * Returns the OpenTelemetry-compatible severity number for this level.
     *
     * @return severity number
     */
    public int severityNumber() {
        return severityNumber;
    }

    /**
     * Returns the single-bit mask representing this level.
     *
     * @return level bit mask
     */
    public int mask() {
        return 1 << ordinal();
    }

    /**
     * Returns whether this configured threshold enables the candidate event.
     *
     * @param candidate event level to test
     * @return {@code true} when the candidate meets or exceeds this threshold
     */
    public boolean enables(Level candidate) {
        return candidate.ordinal() >= ordinal();
    }

    /**
     * Builds a mask containing the threshold and every more severe level.
     *
     * @param threshold lowest enabled level
     * @return enabled-level bit mask
     */
    public static int enabledMaskFrom(Level threshold) {
        int result = 0;
        for (Level level : values()) {
            if (threshold.enables(level)) {
                result |= level.mask();
            }
        }
        return result;
    }

    /**
     * Parses a case-insensitive level name.
     *
     * @param value level name
     * @return parsed level
     * @throws IllegalArgumentException if the value is null or is not a supported level
     */
    public static Level parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("level must be trace, debug, info, warn, or error", exception);
        }
    }
}
