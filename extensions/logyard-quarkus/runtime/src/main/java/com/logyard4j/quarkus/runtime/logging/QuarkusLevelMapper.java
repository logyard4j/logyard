package com.logyard4j.quarkus.runtime.logging;

import com.logyard4j.api.Level;

/** Deterministic mapping from JBoss Log Manager's JUL-compatible levels into Logyard severity. */
final class QuarkusLevelMapper {
    private QuarkusLevelMapper() {
    }

    static Level toLogyard(java.util.logging.Level source) {
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
