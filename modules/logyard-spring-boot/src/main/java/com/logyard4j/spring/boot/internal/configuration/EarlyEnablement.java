package com.logyard4j.spring.boot.internal.configuration;

/** Reads the only property sources available when Spring Boot selects a logging system. */
public final class EarlyEnablement {
    private static final String PROPERTY = "logyard.enabled";
    private static final String ENVIRONMENT_VARIABLE = "LOGYARD_ENABLED";

    private EarlyEnablement() {
    }

    public static boolean isEnabled() {
        String property = System.getProperty(PROPERTY);
        return parse(property == null ? System.getenv(ENVIRONMENT_VARIABLE) : property);
    }

    private static boolean parse(String value) {
        if (value == null || value.isBlank() || "true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        throw new IllegalStateException(PROPERTY + " must be true or false, but was: " + value);
    }
}
