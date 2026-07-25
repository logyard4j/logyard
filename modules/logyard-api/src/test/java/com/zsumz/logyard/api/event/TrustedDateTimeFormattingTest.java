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
    void everyDateFormattingShapeAvoidsTheApplicationTimeZoneObject() {
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
            BoundedMessageFormat.Result display = BoundedMessageFormat.printf(
                    "%s",
                    new Object[] {new Date(0L)});
            MessageFormatter.RenderResult direct = MessageFormatter.safeRender(
                    new Date(0L),
                    CaptureLimits.MAX_TEXT_CHARS);
            Object captured = ValueCapture.capture(new Date(0L));

            assertFalse(message.formatFailed());
            assertFalse(printf.formatFailed());
            assertFalse(display.formatFailed());
            assertTrue(message.message().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
            assertTrue(printf.message().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
            assertEquals("Thu Jan 01 00:00:00 UTC 1970", display.message());
            assertEquals(display.message(), direct.value());
            assertEquals(display.message(), captured);
            assertEquals(1, hostile.cloneCalls.get(), "formatting cloned the application TimeZone");
            assertEquals(0, hostile.behaviorCalls.get(), "formatting invoked application TimeZone behavior");
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void customDatePatternsTakeTheBoundedFormatFailurePathBeforeFormatterConstruction() {
        for (String pattern : new String[] {
                "{0,date,u}",
                "{0,date," + "[".repeat(8_000) + "u}",
                "{0,time," + "zzzz ".repeat(1_600) + "}"
        }) {
            BoundedMessageFormat.Result result =
                    BoundedMessageFormat.messageFormat(pattern, new Object[] {new Date(123L)});

            assertEquals(pattern, result.message());
            assertTrue(result.formatFailed(), pattern);
        }
    }

    @Test
    void temporalZoneIsStableUtcEvenWhenSystemPropertiesAreHostileOrChange() {
        String originalProperty = System.getProperty("user.timezone");
        try {
            System.setProperty("user.timezone", " ".repeat(100_000));
            BoundedMessageFormat.Result first =
                    BoundedMessageFormat.printf("%1$tZ %1$tc", new Object[] {new Date(0L)});
            System.setProperty("user.timezone", "America/Chicago");
            BoundedMessageFormat.Result second =
                    BoundedMessageFormat.printf("%1$tZ %1$tc", new Object[] {new Date(0L)});

            assertEquals("UTC Thu Jan 01 00:00:00 UTC 1970", first.message());
            assertEquals(first.message(), second.message());
        } finally {
            if (originalProperty == null) {
                System.clearProperty("user.timezone");
            } else {
                System.setProperty("user.timezone", originalProperty);
            }
        }
    }

    private static final class HostileTimeZone extends TimeZone {
        private static final long serialVersionUID = 1L;

        private final AtomicInteger cloneCalls = new AtomicInteger();
        private final AtomicInteger behaviorCalls = new AtomicInteger();

        @Override
        public Object clone() {
            if (cloneCalls.incrementAndGet() > 1) {
                throw new AssertionError("application TimeZone was cloned during formatting");
            }
            return super.clone();
        }

        @Override
        public String getDisplayName(boolean daylight, int style, Locale locale) {
            behaviorCalls.incrementAndGet();
            throw new AssertionError("application TimeZone display name was consulted");
        }

        @Override
        public int getOffset(long date) {
            behaviorCalls.incrementAndGet();
            throw new AssertionError("application TimeZone offset was consulted");
        }

        @Override
        public int getOffset(int era, int year, int month, int day, int dayOfWeek, int milliseconds) {
            behaviorCalls.incrementAndGet();
            throw new AssertionError("application TimeZone calendar offset was consulted");
        }

        @Override public void setRawOffset(int offsetMillis) { }
        @Override public int getRawOffset() { behaviorCalls.incrementAndGet(); return 0; }
        @Override public boolean useDaylightTime() { return false; }
        @Override public boolean inDaylightTime(Date date) { return false; }
    }
}
