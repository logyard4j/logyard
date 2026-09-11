package com.logyard4j.canaries;

import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/** Exposes accidental calls into application-owned temporal behavior under a constrained heap. */
final class HostileTimeZone extends TimeZone {
    private static final long serialVersionUID = 1L;

    final AtomicInteger cloneCalls = new AtomicInteger();
    final AtomicInteger behaviorCalls = new AtomicInteger();

    @Override
    public Object clone() {
        if (cloneCalls.incrementAndGet() > 1) {
            allocateWithoutBound();
        }
        return super.clone();
    }

    @Override
    public String getDisplayName(boolean daylight, int style, Locale locale) {
        behaviorCalls.incrementAndGet();
        allocateWithoutBound();
        return "unreachable";
    }

    @Override
    public int getOffset(long date) {
        behaviorCalls.incrementAndGet();
        allocateWithoutBound();
        return 0;
    }

    @Override
    public int getOffset(int era, int year, int month, int day, int dayOfWeek, int milliseconds) {
        behaviorCalls.incrementAndGet();
        allocateWithoutBound();
        return 0;
    }

    @Override public void setRawOffset(int offsetMillis) { }
    @Override public int getRawOffset() { behaviorCalls.incrementAndGet(); allocateWithoutBound(); return 0; }
    @Override public boolean useDaylightTime() { behaviorCalls.incrementAndGet(); allocateWithoutBound(); return false; }
    @Override public boolean inDaylightTime(Date date) { behaviorCalls.incrementAndGet(); allocateWithoutBound(); return false; }

    private static void allocateWithoutBound() {
        byte[] allocation = new byte[100_000_000];
        if (allocation.length == 0) {
            throw new AssertionError("unreachable");
        }
    }
}
