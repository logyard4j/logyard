package com.zsumz.logyard.api.event;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TrustedDateTimeFormattingTest {
    @Test
    void messageAndPrintfDateTimeFormattingNeverConsultTheApplicationTimeZoneObject() {
        TimeZone original = TimeZone.getDefault();
        HostileTimeZone hostile = new HostileTimeZone();
        TimeZone.setDefault(hostile);
        try {
            BoundedMessageFormat.Result message = BoundedMessageFormat.messageFormat(
                    "{0} {0,time,full} {0,date,full}",
                    new Object[] {new Date(0L)});
            BoundedMessageFormat.Result printf = BoundedMessageFormat.printf(
                    "%1$tZ %1$tc",
                    new Object[] {new Date(0L)});

            assertFalse(message.formatFailed());
            assertFalse(printf.formatFailed());
            assertTrue(message.message().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
            assertTrue(printf.message().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
            assertTrue(hostile.displayNameCalls.get() == 0, "application TimeZone display name was consulted");
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void preservesSimpleDateFormatCustomPatternSemantics() {
        BoundedMessageFormat.Result result =
                BoundedMessageFormat.messageFormat("{0,date,S}", new Object[] {new Date(123L)});

        assertEquals("123", result.message());
        assertFalse(result.formatFailed());
    }

    private static final class HostileTimeZone extends TimeZone {
        private static final long serialVersionUID = 1L;

        private final AtomicInteger displayNameCalls = new AtomicInteger();

        @Override
        public String getDisplayName(boolean daylight, int style, Locale locale) {
            displayNameCalls.incrementAndGet();
            return "x".repeat(5_000_000);
        }

        @Override public int getOffset(int era, int year, int month, int day, int dayOfWeek, int milliseconds) { return 0; }
        @Override public void setRawOffset(int offsetMillis) { }
        @Override public int getRawOffset() { return 0; }
        @Override public boolean useDaylightTime() { return false; }
        @Override public boolean inDaylightTime(Date date) { return false; }
    }
}
