package com.logyard4j.logyard.jul.internal.event;

import com.logyard4j.logyard.api.Level;

/** Deterministic mapping from JUL's numeric level space into Logyard severity. */
public final class JulLevelMapper {
    private JulLevelMapper() {
    }

    public static Level toLogyard(java.util.logging.Level source) {
        int value = source.intValue();
        if (value >= java.util.logging.Level.SEVERE.intValue()) {
            return Level.ERROR;
        }
        if (value >= java.util.logging.Level.WARNING.intValue()) {
            return Level.WARN;
        }
        if (value >= java.util.logging.Level.INFO.intValue()) {
            return Level.INFO;
        }
        if (value >= java.util.logging.Level.FINE.intValue()) {
            return Level.DEBUG;
        }
        return Level.TRACE;
    }
}
