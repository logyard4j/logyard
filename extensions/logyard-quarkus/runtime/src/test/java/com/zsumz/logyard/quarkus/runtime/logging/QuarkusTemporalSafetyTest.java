package com.zsumz.logyard.quarkus.runtime.logging;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.context.ContextPolicySnapshot;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuarkusTemporalSafetyTest {
    @Test
    void everyQuarkusDateFormatShapeAvoidsTheApplicationTimeZoneObject() {
        ExtLogRecord messageFormat = record(
                "{0,time,full} {0,date,full}",
                ExtLogRecord.FormatStyle.MESSAGE_FORMAT);
        ExtLogRecord printf = record("%1$tZ %1$tc", ExtLogRecord.FormatStyle.PRINTF);
        ExtLogRecord display = record("%s", ExtLogRecord.FormatStyle.PRINTF);
        HostileTimeZone hostile = new HostileTimeZone();
        TimeZone original = TimeZone.getDefault();
        List<LogEvent> events = new ArrayList<>();
        TimeZone.setDefault(hostile);
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            QuarkusEventMapper mapper = new QuarkusEventMapper(ContextPolicySnapshot::all);
            mapper.publish(runtime, messageFormat);
            mapper.publish(runtime, printf);
            mapper.publish(runtime, display);
        } finally {
            TimeZone.setDefault(original);
        }

        assertEquals(3, events.size());
        assertTrue(events.get(0).renderedMessage().length() < 1_024);
        assertTrue(events.get(1).renderedMessage().length() < 1_024);
        assertEquals("Thu Jan 01 00:00:00 UTC 1970", events.get(2).renderedMessage());
        assertEquals(1, hostile.cloneCalls.get());
        assertEquals(0, hostile.behaviorCalls.get());
    }

    private static ExtLogRecord record(String pattern, ExtLogRecord.FormatStyle style) {
        ExtLogRecord record = new ExtLogRecord(
                java.util.logging.Level.INFO,
                pattern,
                style,
                QuarkusTemporalSafetyTest.class.getName());
        record.setParameters(new Object[] {new Date(0L)});
        return record;
    }

    private static final class HostileTimeZone extends TimeZone {
        private static final long serialVersionUID = 1L;

        private final AtomicInteger cloneCalls = new AtomicInteger();
        private final AtomicInteger behaviorCalls = new AtomicInteger();

        @Override
        public Object clone() {
            if (cloneCalls.incrementAndGet() > 1) {
                throw new AssertionError("Quarkus formatting cloned the application TimeZone");
            }
            return super.clone();
        }

        @Override
        public String getDisplayName(boolean daylight, int style, Locale locale) {
            behaviorCalls.incrementAndGet();
            throw new AssertionError("Quarkus formatting consulted the application TimeZone display name");
        }

        @Override
        public int getOffset(long date) {
            behaviorCalls.incrementAndGet();
            throw new AssertionError("Quarkus formatting consulted the application TimeZone offset");
        }

        @Override
        public int getOffset(int era, int year, int month, int day, int dayOfWeek, int milliseconds) {
            behaviorCalls.incrementAndGet();
            throw new AssertionError("Quarkus formatting consulted the application TimeZone calendar offset");
        }

        @Override public void setRawOffset(int offsetMillis) { }
        @Override public int getRawOffset() { behaviorCalls.incrementAndGet(); return 0; }
        @Override public boolean useDaylightTime() { return false; }
        @Override public boolean inDaylightTime(Date date) { return false; }
    }
}
