package com.zsumz.logyard.runtime.management;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.core.level.RuntimeLevelOverride;

/** Operational logger threshold, including a fully disabled state. */
public enum LoggerLevel {
    TRACE(Level.TRACE),
    DEBUG(Level.DEBUG),
    INFO(Level.INFO),
    WARN(Level.WARN),
    ERROR(Level.ERROR),
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
