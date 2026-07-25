package com.zsumz.logyard.api.event;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Resolves a bounded zone identifier without consulting the process-default {@link java.util.TimeZone} object. */
final class TrustedFormattingZone {
    private static final int MAX_ZONE_ID_CHARS = 256;

    private TrustedFormattingZone() {
    }

    static ZoneId current() {
        String configured;
        try {
            configured = System.getProperty("user.timezone");
        } catch (SecurityException unavailable) {
            return ZoneOffset.UTC;
        }
        if (!safeIdentifier(configured)) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(configured);
        } catch (DateTimeException invalid) {
            return ZoneOffset.UTC;
        }
    }

    private static boolean safeIdentifier(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ZONE_ID_CHARS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character) && "_+-./:".indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }
}
