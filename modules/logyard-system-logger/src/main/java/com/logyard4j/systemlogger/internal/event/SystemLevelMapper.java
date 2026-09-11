package com.logyard4j.systemlogger.internal.event;

import com.logyard4j.api.Level;

/** Mapping for the JDK's deliberately small System.Logger level model. */
public final class SystemLevelMapper {
    private SystemLevelMapper() {
    }

    public static Level toLogyard(System.Logger.Level source) {
        return switch (source) {
            case ALL, TRACE -> Level.TRACE;
            case DEBUG -> Level.DEBUG;
            case INFO -> Level.INFO;
            case WARNING -> Level.WARN;
            case ERROR -> Level.ERROR;
            case OFF -> throw new IllegalArgumentException("OFF has no Logyard severity");
        };
    }
}
