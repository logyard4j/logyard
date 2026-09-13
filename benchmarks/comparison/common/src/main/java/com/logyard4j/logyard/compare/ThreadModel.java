package com.logyard4j.logyard.compare;

/** Distinguishes persistent producers from one fresh virtual thread per request. */
public enum ThreadModel {
    PLATFORM,
    VIRTUAL,
    VIRTUAL_PER_REQUEST;

    static ThreadModel parse(String value) {
        return switch (value) {
            case "false" -> PLATFORM;
            case "true" -> VIRTUAL;
            case "per-request" -> VIRTUAL_PER_REQUEST;
            default -> throw new IllegalArgumentException("unknown comparison thread model: " + value);
        };
    }
}
