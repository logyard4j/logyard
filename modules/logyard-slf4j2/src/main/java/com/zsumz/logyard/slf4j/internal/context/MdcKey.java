package com.zsumz.logyard.slf4j.internal.context;

import com.zsumz.logyard.api.event.CaptureLimits;

/** Validates MDC keys without introducing collisions through truncation. */
final class MdcKey {
    private MdcKey() {
    }

    /** Returns null for oversized keys; null caller keys violate the SLF4J contract. */
    static String bounded(String key) {
        if (key == null) {
            throw new IllegalArgumentException("SLF4J MDC key must not be null");
        }
        return key.length() > CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS ? null : key;
    }

    /** A rejected stack push must fail explicitly so a later pop cannot consume an older value. */
    static String stackKey(String key) {
        String bounded = bounded(key);
        if (bounded == null) {
            throw new IllegalArgumentException("SLF4J MDC deque key exceeds the capture limit");
        }
        return bounded;
    }
}
