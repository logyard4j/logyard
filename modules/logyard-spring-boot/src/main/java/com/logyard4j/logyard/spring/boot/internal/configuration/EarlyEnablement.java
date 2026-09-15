package com.logyard4j.logyard.spring.boot.internal.configuration;

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
        if (value == null || value.isBlank()) {
            return true;
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) <= ' ') {
            end--;
        }
        if (end - start == 4 && value.regionMatches(true, start, "true", 0, 4)) {
            return true;
        }
        if (end - start == 5 && value.regionMatches(true, start, "false", 0, 5)) {
            return false;
        }
        String displayed = value.length() <= 128
                ? value
                : value.substring(start, Math.min(end, start + 128)) + (end - start > 128 ? "..." : "");
        throw new IllegalStateException(PROPERTY + " must be true or false, but was: " + displayed);
    }
}
