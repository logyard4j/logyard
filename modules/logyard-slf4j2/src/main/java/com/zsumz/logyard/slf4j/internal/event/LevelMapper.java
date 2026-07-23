package com.zsumz.logyard.slf4j.internal.event;

import com.zsumz.logyard.api.Level;

import java.util.Objects;

/** Exhaustive mapping between SLF4J and Logyard severity models. */
public final class LevelMapper {
    private LevelMapper() {
    }

    public static Level toLogyard(org.slf4j.event.Level level) {
        return switch (Objects.requireNonNull(level, "level")) {
            case TRACE -> Level.TRACE;
            case DEBUG -> Level.DEBUG;
            case INFO -> Level.INFO;
            case WARN -> Level.WARN;
            case ERROR -> Level.ERROR;
        };
    }
}
