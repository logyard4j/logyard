package com.zsumz.logyard.api.event;

import com.zsumz.logyard.api.Level;
import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventCaptureBudgetTest {
    @Test
    void sharesOneBudgetAndCompletedIdentityAcrossDeferredEventFields() {
        List<Object> shared = new ArrayList<>(List.of(Map.of("value", "captured")));

        LogEvent event = LogEvent.captureDeferred(
                1L,
                2L,
                Level.INFO,
                "test.capture",
                null,
                "{}",
                () -> new Object[] {shared, shared},
                () -> AttributeSet.builder().put("shared", shared).build(),
                2,
                null,
                3L,
                "main");

        assertSame(event.argumentAt(0), event.argumentAt(1));
        assertSame(event.argumentAt(0), event.attributes().get("shared"));
    }

    @Test
    void boundsGenericIteratorsWithoutCallingSize() {
        AtomicInteger collectionReads = new AtomicInteger();
        AtomicInteger mapReads = new AtomicInteger();
        Object[] source = {
                collection(1_000, collectionReads),
                map(1_000, mapReads)
        };

        LogEvent event = event(source, AttributeSet.EMPTY, null);

        assertEquals(CaptureLimits.MAX_COLLECTION_ELEMENTS + 1, collectionReads.get());
        assertEquals(CaptureLimits.MAX_COLLECTION_ELEMENTS + 1, mapReads.get());
        assertEquals(CaptureLimits.MAX_COLLECTION_ELEMENTS + 1, ((List<?>) event.argumentAt(0)).size());
        assertTrue(((Map<?, ?>) event.argumentAt(1)).containsKey("logyard.truncated"));
    }

    @Test
    void stopsExpandingWhenTheAggregateEntryBudgetIsExhausted() {
        Object[] arguments = new Object[CaptureLimits.MAX_ARGUMENTS];
        for (int argument = 0; argument < arguments.length; argument++) {
            List<Integer> values = new ArrayList<>();
            for (int value = 0; value < CaptureLimits.MAX_COLLECTION_ELEMENTS; value++) {
                values.add(argument * CaptureLimits.MAX_COLLECTION_ELEMENTS + value);
            }
            arguments[argument] = values;
        }

        LogEvent event = event(arguments, AttributeSet.EMPTY, null);

        assertEquals(true, event.attributes().get("logyard.capture.truncated"));
    }

    @Test
    void doesNotReadAnotherCallerEntryAfterTheAggregateBudgetIsExhausted() {
        CaptureContext context = CaptureContext.create();
        while (context.claimEntry()) {
            // Exhaust the event-wide entry allowance before touching caller-owned iteration.
        }
        AtomicInteger reads = new AtomicInteger();

        List<?> captured = (List<?>) ValueCapture.capture(collection(1_000, reads), context);

        assertEquals(0, reads.get());
        assertEquals(List.of("[event capture budget exhausted]"), captured);
    }

    @Test
    void reusesCompletedThrowableSnapshotsAndBoundsRenderedTextWithoutArguments() {
        IllegalStateException shared = new IllegalStateException("shared");
        RuntimeException parent = new RuntimeException("parent");
        parent.addSuppressed(shared);
        parent.addSuppressed(shared);

        LogEvent event = event(null, AttributeSet.EMPTY, parent);
        ExceptionSnapshot snapshot = event.exception();

        assertSame(snapshot.suppressed().get(0), snapshot.suppressed().get(1));
        String rendered = MessageFormatter.format("x".repeat(CaptureLimits.MAX_TEXT_CHARS + 1_000), null);
        assertEquals(CaptureLimits.MAX_TEXT_CHARS, rendered.length());
        assertTrue(rendered.endsWith("…"));
    }

    @Test
    void reservesTheSystemNamespaceAndLetsFinalMarkersWin() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AttributeSet.builder().put("logyard.arguments.omitted", 99));
        assertThrows(
                IllegalArgumentException.class,
                () -> AttributeSet.of("LOGYARD.capture.truncated", false));

        AttributeSet forged = new AttributeSet(
                new String[] {"logyard.arguments.omitted"},
                new Object[] {99});
        Object[] arguments = new Object[CaptureLimits.MAX_ARGUMENTS + 3];
        LogEvent event = event(arguments, forged, null);

        assertEquals(3, event.attributes().get("logyard.arguments.omitted"));
    }

    private static LogEvent event(Object[] arguments, AttributeSet attributes, Throwable throwable) {
        return new LogEvent(
                1L,
                2L,
                Level.INFO,
                "test.capture",
                null,
                "message",
                arguments,
                attributes,
                throwable,
                3L,
                "main");
    }

    private static AbstractCollection<Integer> collection(int count, AtomicInteger reads) {
        return new AbstractCollection<>() {
            @Override
            public Iterator<Integer> iterator() {
                return iteratorOf(count, reads);
            }

            @Override
            public int size() {
                throw new AssertionError("generic collection size must not be read");
            }
        };
    }

    private static AbstractMap<String, Integer> map(int count, AtomicInteger reads) {
        return new AbstractMap<>() {
            @Override
            public Set<Entry<String, Integer>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Entry<String, Integer>> iterator() {
                        Iterator<Integer> values = iteratorOf(count, reads);
                        return new Iterator<>() {
                            @Override public boolean hasNext() { return values.hasNext(); }
                            @Override public Entry<String, Integer> next() {
                                int value = values.next();
                                return Map.entry("key." + value, value);
                            }
                        };
                    }

                    @Override
                    public int size() {
                        throw new AssertionError("generic map size must not be read");
                    }
                };
            }
        };
    }

    private static Iterator<Integer> iteratorOf(int count, AtomicInteger reads) {
        return new Iterator<>() {
            private int next;

            @Override public boolean hasNext() { return next < count; }
            @Override public Integer next() {
                reads.incrementAndGet();
                return next++;
            }
        };
    }
}
