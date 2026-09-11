package com.logyard4j.slf4j.internal.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.event.CaptureLimits;
import com.logyard4j.runtime.context.ContextPolicySnapshot;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LogyardMdcAdapterTest {
    @Test
    void snapshotsAreAllowlistedAndDefensive() {
        LogyardMdcAdapter mdc = new LogyardMdcAdapter();
        mdc.put("request.id", "request-1");
        mdc.put("tenant.id", "tenant-7");
        mdc.put("ignored", "nope");

        Map<String, String> copy = mdc.getCopyOfContextMap();
        copy.put("mutated", "outside");
        assertNull(mdc.get("mutated"));

        ContextSnapshotPolicy selected =
                new ContextSnapshotPolicy(List.of("request.id", "tenant.id"));
        assertEquals("request-1", selected.capture(mdc).get("request.id"));
        assertEquals("tenant-7", selected.capture(mdc).get("tenant.id"));
        assertNull(selected.capture(mdc).get("ignored"));

        ContextSnapshotPolicy all = new ContextSnapshotPolicy(List.of("*"));
        assertEquals("nope", all.capture(mdc).get("ignored"));
    }

    @Test
    void mapValuesAreNotInheritedByChildThreads() throws Exception {
        LogyardMdcAdapter mdc = new LogyardMdcAdapter();
        mdc.put("request.id", "parent");
        AtomicReference<String> childValue = new AtomicReference<>("not-run");
        Thread child = new Thread(() -> childValue.set(mdc.get("request.id")));
        child.start();
        child.join();
        assertNull(childValue.get());
        assertEquals("parent", mdc.get("request.id"));
    }

    @Test
    void mapAndDequeLifecyclesRemainIndependentAndBounded() {
        LogyardMdcAdapter mdc = new LogyardMdcAdapter();
        mdc.put("request.id", "one");
        mdc.pushByKey("scope", "outer");
        mdc.pushByKey("scope", "inner");
        mdc.clear();
        assertNull(mdc.get("request.id"));
        assertEquals("inner", mdc.popByKey("scope"));
        assertEquals("outer", mdc.popByKey("scope"));
        assertNull(mdc.popByKey("scope"));

        mdc.setContextMap(Map.of("request.id", "two"));
        assertEquals("two", mdc.get("request.id"));
        mdc.setContextMap(null);
        assertNull(mdc.get("request.id"));

        for (int index = 0; index < LogyardMdcAdapter.MAX_STACK_DEPTH; index++) {
            mdc.pushByKey("bounded", "v" + index);
        }
        assertThrows(IllegalStateException.class, () -> mdc.pushByKey("bounded", "overflow"));
        ArrayDeque<String> copy = new ArrayDeque<>(mdc.getCopyOfDequeByKey("bounded"));
        copy.clear();
        assertEquals(
                LogyardMdcAdapter.MAX_STACK_DEPTH,
                mdc.getCopyOfDequeByKey("bounded").size());
        mdc.clearDequeByKey("bounded");
        assertNull(mdc.getCopyOfDequeByKey("bounded"));
    }


    @Test
    void setContextMapUsesBoundedIterationInsteadOfAdvisoryCollectionMethods() {
        LogyardMdcAdapter mdc = new LogyardMdcAdapter();
        mdc.put("existing", "preserved-until-commit");
        Map<String, String> hostile = new AbstractMap<>() {
            @Override
            public Set<Entry<String, String>> entrySet() {
                LinkedHashSet<Entry<String, String>> entries = new LinkedHashSet<>();
                entries.add(Map.entry("request.id", "request-9"));
                entries.add(Map.entry("tenant.id", "tenant-3"));
                return entries;
            }

            @Override
            public int size() {
                throw new AssertionError("size must not be trusted");
            }

            @Override
            public boolean isEmpty() {
                throw new AssertionError("isEmpty must not be trusted");
            }
        };

        mdc.setContextMap(hostile);
        assertNull(mdc.get("existing"));
        assertEquals("request-9", mdc.get("request.id"));
        assertEquals("tenant-3", mdc.get("tenant.id"));
    }

    @Test
    void boundsKeysAndEntryCountsWithoutThrowingIntoTheApplication() {
        LogyardMdcAdapter mdc = new LogyardMdcAdapter();
        assertThrows(IllegalArgumentException.class, () -> mdc.put(null, "value"));

        mdc.put(" ", "blank-key-value");
        assertEquals("blank-key-value", mdc.get(" "), "blank keys are stored faithfully");

        String oversized = "x".repeat(CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS + 32);
        String prefix = oversized.substring(0, CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
        mdc.put(prefix, "original");
        mdc.put(oversized, "value");
        assertNull(mdc.get(oversized), "oversized keys are dropped without aliasing another key");
        assertEquals("original", mdc.get(prefix));
        mdc.remove(oversized);
        assertEquals("original", mdc.get(prefix));
        assertTrue(mdc.captureLossy());
        assertThrows(IllegalArgumentException.class, () -> mdc.pushByKey(oversized, "value"));
        mdc.clear();

        for (int index = 0; index < LogyardMdcAdapter.MAX_ENTRIES; index++) {
            mdc.put("key." + index, "value");
        }
        mdc.put("one.too.many", "value");
        assertNull(mdc.get("one.too.many"), "an entry beyond the bound is dropped, not thrown");
        assertEquals("value", mdc.get("key.0"), "existing entries survive a dropped put");
        mdc.put("key.0", "replacement");
        assertEquals("replacement", mdc.get("key.0"), "replacing an existing key still works at the bound");

        assertTrue(mdc.captureLossy(), "dropped MDC entries surface to event capture as loss");
        mdc.clear();
        assertFalse(mdc.captureLossy(), "clearing the MDC resets the loss signal");
    }
    @Test
    void preservesConfiguredAllowlistOrder() {
        ContextSnapshotPolicy policy = new ContextSnapshotPolicy(List.of("trace.id", "request.id"));
        assertEquals(List.of("trace.id", "request.id"), List.copyOf(policy.includedKeys()));
    }

    @Test
    void readsOnePublishedPolicySnapshotPerCaptureAndObservesUpdates() {
        LogyardMdcAdapter mdc = new LogyardMdcAdapter();
        mdc.put("request.id", "request-1");
        mdc.put("tenant.id", "tenant-7");
        AtomicReference<ContextPolicySnapshot> current =
                new AtomicReference<>(ContextPolicySnapshot.of(List.of("request.id")));
        AtomicInteger reads = new AtomicInteger();
        ContextSnapshotPolicy policy = new ContextSnapshotPolicy(() -> {
            reads.incrementAndGet();
            return current.get();
        });

        assertEquals("request-1", policy.capture(mdc).get("request.id"));
        assertEquals(1, reads.get());

        current.set(ContextPolicySnapshot.of(List.of("tenant.id")));
        assertEquals("tenant-7", policy.capture(mdc).get("tenant.id"));
        assertEquals(2, reads.get());
    }

    @Test
    void marksContextTruncatedWhenTheBoundedEventCannotRetainEveryMdcEntry() {
        LogyardMdcAdapter mdc = new LogyardMdcAdapter();
        for (int index = 0; index < LogyardMdcAdapter.MAX_ENTRIES; index++) {
            mdc.put("context." + index, "value-" + index);
        }

        var captured = new ContextSnapshotPolicy(List.of("*")).capture(mdc);

        assertEquals(CaptureLimits.MAX_ATTRIBUTES, captured.size());
        assertTrue((Boolean) captured.get("logyard.attributes.truncated"));
        assertNull(captured.get("context." + (LogyardMdcAdapter.MAX_ENTRIES - 1)));
    }

}
