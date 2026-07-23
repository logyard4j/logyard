package com.zsumz.logyard.slf4j.internal.context;

import com.zsumz.logyard.api.event.CaptureLimits;

import java.util.Objects;

final class MdcKey {
    private MdcKey() {
    }

    static String requireValid(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("SLF4J MDC key must not be blank");
        }
        if (key.length() > CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS) {
            throw new IllegalArgumentException(
                    "SLF4J MDC key exceeds " + CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS + " characters");
        }
        return key;
    }
}
