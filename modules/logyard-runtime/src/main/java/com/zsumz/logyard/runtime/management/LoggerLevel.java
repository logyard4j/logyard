package com.zsumz.logyard.runtime.management;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.core.level.RuntimeLevelOverride;

/** Operational logger threshold, including a fully disabled state. */
public enum LoggerLevel {
    /** Trace and more severe events are enabled. */
    TRACE(Level.TRACE),
    /** Debug and more severe events are enabled. */
    DEBUG(Level.DEBUG),
    /** Informational and more severe events are enabled. */
    INFO(Level.INFO),
    /** Warning and error events are enabled. */
    WARN(Level.WARN),
    /** Only error events are enabled. */
    ERROR(Level.ERROR),
    /** All events are disabled. */
    OFF(null);

    private final Level eventLevel;

    LoggerLevel(Level eventLevel) {
        this.eventLevel = eventLevel;
    }

    RuntimeLevelOverride toOverride() {
        return this == OFF ? RuntimeLevelOverride.off() : RuntimeLevelOverride.threshold(eventLevel);
    }

    static LoggerLevel from(RuntimeLevelOverride override) {
        return override.disabled() ? OFF : valueOf(override.threshold().name());
    }

    static LoggerLevel from(Level level) {
        return valueOf(level.name());
    }
}
