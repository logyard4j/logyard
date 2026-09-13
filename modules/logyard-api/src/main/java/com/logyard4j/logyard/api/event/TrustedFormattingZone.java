package com.logyard4j.logyard.api.event;

import java.time.ZoneId;

/** Deterministic temporal policy for adapter formatting. */
final class TrustedFormattingZone {
    private static final ZoneId UTC = ZoneId.of("UTC");

    private TrustedFormattingZone() {
    }

    static ZoneId defaultZone() {
        return UTC;
    }
}
