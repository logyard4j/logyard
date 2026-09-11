package com.zsumz.logyard.quarkus.runtime.logging;

import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.context.ContextPolicySnapshot;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuarkusEventMapperTest {
    @Test
    void capturesBoundedMdcWithoutSortingAndMarksEveryOverflowBoundary() {
        for (int entries : List.of(0, 4, 127, 128, 129, 10_000)) {
            List<LogEvent> events = new ArrayList<>();
            TrackingRecord record = new TrackingRecord(entries);
            try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
                new QuarkusEventMapper(ContextPolicySnapshot::all).publish(runtime, record);
            }

            LogEvent event = events.getFirst();
            assertTrue(event.attributes().size() <= CaptureLimits.MAX_ATTRIBUTES);
            if (entries <= CaptureLimits.MAX_ATTRIBUTES - 3) {
                assertNull(event.attributes().get("logyard.attributes.truncated"), "entry count " + entries);
                assertEquals(entries, record.visitedEntries());
            } else {
                assertTrue((Boolean) event.attributes().get("logyard.attributes.truncated"), "entry count " + entries);
                assertTrue(record.visitedEntries() < entries, "entry count " + entries);
            }
        }
    }

    @Test
    void finiteAllowlistUsesOnlyKeyedLookupsEvenForHugeSourceContext() {
        List<LogEvent> events = new ArrayList<>();
        TrackingRecord record = new TrackingRecord(10_000);
        ContextPolicySnapshot selected = ContextPolicySnapshot.of(List.of("context.9999", "context.1"));
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            new QuarkusEventMapper(() -> selected).publish(runtime, record);
        }

        LogEvent event = events.getFirst();
        assertFalse(record.fullCopyRequested());
        assertEquals(2, record.keyedLookups());
        assertTrue(indexOf(event, "context.9999") < indexOf(event, "context.1"));
    }

    @Test
    void ndcIsOmittedWithAnExplicitMarkerWhenMdcConsumesTheBudget() {
        List<LogEvent> events = new ArrayList<>();
        TrackingRecord record = new TrackingRecord(CaptureLimits.MAX_ATTRIBUTES - 3);
        record.setNdc("operation");
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            new QuarkusEventMapper(ContextPolicySnapshot::all).publish(runtime, record);
        }

        LogEvent event = events.getFirst();
        assertEquals(CaptureLimits.MAX_ATTRIBUTES, event.attributes().size());
        assertNull(event.attributes().get("quarkus.ndc"));
        assertTrue((Boolean) event.attributes().get("logyard.attributes.truncated"));
    }

    @Test
    void preservesCaptureTruncationFromMdcAndSourceMetadata() {
        List<LogEvent> events = new ArrayList<>();
        ExtLogRecord record = new ExtLogRecord(
                java.util.logging.Level.INFO,
                "mapped",
                ExtLogRecord.FormatStyle.NO_FORMAT,
                QuarkusEventMapperTest.class.getName());
        record.setLoggerName("test.Quarkus");
        record.setSourceClassName("x".repeat(CaptureLimits.MAX_TEXT_CHARS + 1));
        record.putMdc("oversized", "y".repeat(CaptureLimits.MAX_TEXT_CHARS + 1));
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            new QuarkusEventMapper(ContextPolicySnapshot::all).publish(runtime, record);
        }

        assertEquals(true, events.getFirst().attributes().get("logyard.capture.truncated"));
    }

    @Test
    void boundsMessageFormatNumbersAndPrintfWidthsBeforeMapping() {
        ExtLogRecord messageFormat = new ExtLogRecord(
                java.util.logging.Level.INFO,
                "{0}",
                ExtLogRecord.FormatStyle.MESSAGE_FORMAT,
                QuarkusEventMapperTest.class.getName());
        messageFormat.setParameters(new Object[] {new BigDecimal(BigInteger.ONE, -100_000_000)});
        ExtLogRecord printf = new ExtLogRecord(
                java.util.logging.Level.INFO,
                "%100000000s",
                ExtLogRecord.FormatStyle.PRINTF,
                QuarkusEventMapperTest.class.getName());
        printf.setParameters(new Object[] {"bounded"});
        ExtLogRecord repeatedMessageFormat = new ExtLogRecord(
                java.util.logging.Level.INFO,
                "{0}".repeat(CaptureLimits.MAX_EVENT_TEMPLATE_CHARS / 3),
                ExtLogRecord.FormatStyle.MESSAGE_FORMAT,
                QuarkusEventMapperTest.class.getName());
        repeatedMessageFormat.setParameters(new Object[] {"x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS)});
        ExtLogRecord repeatedPrintf = new ExtLogRecord(
                java.util.logging.Level.INFO,
                "%1$s".repeat(CaptureLimits.MAX_EVENT_TEMPLATE_CHARS / 4),
                ExtLogRecord.FormatStyle.PRINTF,
                QuarkusEventMapperTest.class.getName());
        repeatedPrintf.setParameters(new Object[] {"x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS)});
        ExtLogRecord recursiveChoice = new ExtLogRecord(
                java.util.logging.Level.INFO,
                "{0,choice,0#" + "'{1}'".repeat(1_600) + "}",
                ExtLogRecord.FormatStyle.MESSAGE_FORMAT,
                QuarkusEventMapperTest.class.getName());
        recursiveChoice.setParameters(new Object[] {0, "x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS)});
        ExtLogRecord repeatedDefaultNumber = new ExtLogRecord(
                java.util.logging.Level.INFO,
                "{0}".repeat(490),
                ExtLogRecord.FormatStyle.MESSAGE_FORMAT,
                QuarkusEventMapperTest.class.getName());
        repeatedDefaultNumber.setParameters(new Object[] {new BigDecimal(BigInteger.ONE, -2_048)});

        List<LogEvent> events = new ArrayList<>();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            new QuarkusEventMapper(ContextPolicySnapshot::all).publish(runtime, messageFormat);
            new QuarkusEventMapper(ContextPolicySnapshot::all).publish(runtime, printf);
            new QuarkusEventMapper(ContextPolicySnapshot::all).publish(runtime, repeatedMessageFormat);
            new QuarkusEventMapper(ContextPolicySnapshot::all).publish(runtime, repeatedPrintf);
            new QuarkusEventMapper(ContextPolicySnapshot::all).publish(runtime, recursiveChoice);
            new QuarkusEventMapper(ContextPolicySnapshot::all).publish(runtime, repeatedDefaultNumber);
        }

        assertTrue(events.get(0).renderedMessage().contains("1E+100000000"));
        assertTrue((Boolean) events.get(0).attributes().get("logyard.capture.truncated"));
        assertTrue(events.get(1).renderedMessage().length() <= CaptureLimits.MAX_CAPTURED_NUMBER_CHARS);
        assertTrue((Boolean) events.get(1).attributes().get("logyard.capture.truncated"));
        assertTrue(events.get(2).renderedMessage().contains("format expansion omitted"));
        assertTrue((Boolean) events.get(2).attributes().get("logyard.capture.truncated"));
        assertTrue(events.get(3).renderedMessage().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
        assertTrue(events.get(3).renderedMessage().endsWith("…"));
        assertTrue((Boolean) events.get(3).attributes().get("logyard.capture.truncated"));
        assertTrue(events.get(4).renderedMessage().contains("format expansion omitted"));
        assertTrue((Boolean) events.get(4).attributes().get("logyard.capture.truncated"));
        assertTrue(events.get(5).renderedMessage().contains("format expansion omitted"));
        assertTrue((Boolean) events.get(5).attributes().get("logyard.capture.truncated"));
    }

    private static final class TrackingRecord extends ExtLogRecord {
        private static final long serialVersionUID = 1L;

        private final transient Map<String, String> mdc;
        private final transient AtomicInteger visited = new AtomicInteger();
        private final transient AtomicInteger keyedLookups = new AtomicInteger();
        private boolean fullCopyRequested;

        TrackingRecord(int entries) {
            super(
                    java.util.logging.Level.INFO,
                    "mapped",
                    ExtLogRecord.FormatStyle.NO_FORMAT,
                    QuarkusEventMapperTest.class.getName());
            setLoggerName("test.Quarkus");
            mdc = new LinkedHashMap<>(entries);
            for (int index = 0; index < entries; index++) {
                mdc.put("context." + index, "value-" + index);
            }
        }

        @Override
        public String getMdc(String key) {
            keyedLookups.incrementAndGet();
            return mdc.get(key);
        }

        @Override
        public Map<String, String> getMdcCopy() {
            fullCopyRequested = true;
            return new AbstractMap<>() {
                @Override
                public Set<Entry<String, String>> entrySet() {
                    return new java.util.AbstractSet<>() {
                        @Override
                        public java.util.Iterator<Entry<String, String>> iterator() {
                            java.util.Iterator<Entry<String, String>> delegate = mdc.entrySet().iterator();
                            return new java.util.Iterator<>() {
                                @Override public boolean hasNext() { return delegate.hasNext(); }
                                @Override public Entry<String, String> next() {
                                    visited.incrementAndGet();
                                    return delegate.next();
                                }
                            };
                        }

                        @Override
                        public int size() {
                            return mdc.size();
                        }
                    };
                }
            };
        }

        int visitedEntries() {
            return visited.get();
        }

        int keyedLookups() {
            return keyedLookups.get();
        }

        boolean fullCopyRequested() {
            return fullCopyRequested;
        }
    }

    private static int indexOf(LogEvent event, String key) {
        for (int index = 0; index < event.attributes().size(); index++) {
            if (key.equals(event.attributes().keyAt(index))) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }
}
