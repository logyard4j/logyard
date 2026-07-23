package com.zsumz.logyard.api;

import java.util.Locale;

/** Severity ordered from least to most severe. */
public enum Level {
    TRACE(1),
    DEBUG(5),
    INFO(9),
    WARN(13),
    ERROR(17);

    private final int severityNumber;

    Level(int severityNumber) {
        this.severityNumber = severityNumber;
    }

    public int severityNumber() {
        return severityNumber;
    }

    public int mask() {
        return 1 << ordinal();
    }

    /** Returns whether this configured threshold enables the candidate event. */
    public boolean enables(Level candidate) {
        return candidate.ordinal() >= ordinal();
    }

    public static int enabledMaskFrom(Level threshold) {
        int result = 0;
        for (Level level : values()) {
            if (threshold.enables(level)) {
                result |= level.mask();
            }
        }
        return result;
    }

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
