package com.zsumz.logyard.quarkus.runtime.logging;

import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.context.ContextPolicySnapshot;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuarkusMdcNamespaceTest {
    @Test
    void preservesLiteralNamesAndDropsInvalidOrOversizedKeysWithDiagnostics() {
        ExtLogRecord record = new ExtLogRecord(java.util.logging.Level.INFO, "record", getClass().getName());
        record.putMdc("request.id", "r-1");
        record.putMdc("logyard.capture.truncated", "spoofed");
        record.putMdc(" ", "blank");
        record.putMdc("x".repeat(CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS + 1), "oversized");
        LogEvent event = capture(record);
        assertEquals("r-1", event.attributes().get("request.id"));
        assertNull(event.attributes().get("mdc.request.id"));
        assertEquals(Boolean.TRUE, event.attributes().get("logyard.capture.truncated"));
        assertTrue(event.attributes().toMap().values().stream().noneMatch(
                value -> "spoofed".equals(value) || "blank".equals(value) || "oversized".equals(value)));
    }

    @Test
    void skippedKeysCannotMakeWildcardTraversalUnbounded() {
        var inspected = new AtomicInteger();
        ExtLogRecord record = new ExtLogRecord(java.util.logging.Level.INFO, "record", getClass().getName()) {
            @Override public Map<String, String> getMdcCopy() {
                return new AbstractMap<>() {
                    @Override public Set<Entry<String, String>> entrySet() {
                        return new AbstractSet<>() {
                            @Override public int size() { throw new AssertionError("size must not be read"); }
                            @Override public Iterator<Entry<String, String>> iterator() {
                                return new Iterator<>() {
                                    @Override public boolean hasNext() { return true; }
                                    @Override public Entry<String, String> next() {
                                        int index = inspected.incrementAndGet();
                                        if (index > CaptureLimits.MAX_ATTRIBUTES) throw new AssertionError("unbounded traversal");
                                        return Map.entry("logyard.reserved." + index, "value");
                                    }
                                };
                            }
                        };
                    }
                };
            }
        };
        LogEvent event = capture(record);
        assertEquals(CaptureLimits.MAX_ATTRIBUTES, inspected.get());
        assertEquals(Boolean.TRUE, event.attributes().get("logyard.capture.truncated"));
    }

    private static LogEvent capture(ExtLogRecord record) {
        List<LogEvent> events = new ArrayList<>();
        try (var runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            new QuarkusEventMapper(ContextPolicySnapshot::all).publish(runtime, record);
        }
        assertEquals(1, events.size());
        return events.getFirst();
    }
}
