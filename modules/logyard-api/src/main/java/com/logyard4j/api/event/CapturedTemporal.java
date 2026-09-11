package com.logyard4j.api.event;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;

/** Immutable epoch-millisecond token replacing caller-owned legacy temporal objects at the capture boundary. */
record CapturedTemporal(long epochMillis) {
    static CapturedTemporal from(Date value) {
        return new CapturedTemporal(value.getTime());
    }

    ZonedDateTime at(ZoneId zone) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone);
    }

    @Override
    public String toString() {
        return TrustedDateTimeRenderer.display(this);
    }
}
