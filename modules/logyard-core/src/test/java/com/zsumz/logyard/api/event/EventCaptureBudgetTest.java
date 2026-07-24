package com.zsumz.logyard.api.event;

import com.zsumz.logyard.api.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zsumz.logyard.api.event.EventCaptureTestValues.capturedDepth;
import static com.zsumz.logyard.api.event.EventCaptureTestValues.collection;
import static com.zsumz.logyard.api.event.EventCaptureTestValues.map;
import static com.zsumz.logyard.api.event.EventCaptureTestValues.retainedCharacters;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventCaptureBudgetTest {
    @Test
    void eventTextPartitionsExactlyMatchTheDocumentedEnvelope() {
        assertEquals(
                CaptureLimits.MAX_EVENT_TEXT_CHARS,
                CaptureLimits.MAX_EVENT_IDENTITY_CHARS
                        + CaptureLimits.MAX_EVENT_TEMPLATE_CHARS
                        + CaptureLimits.MAX_RENDERED_MESSAGE_CHARS
                        + CaptureLimits.MAX_EVENT_EXCEPTION_TEXT_CHARS
                        + CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);
        assertEquals(
                CaptureLimits.MAX_EVENT_EXCEPTION_TEXT_CHARS,
                CaptureLimits.MAX_EVENT_EXCEPTION_TYPE_CHARS
                        + CaptureLimits.MAX_EVENT_EXCEPTION_MESSAGE_CHARS
                        + CaptureLimits.MAX_EVENT_EXCEPTION_FRAME_CHARS);
    }

    @Test
    void cutsSharedContainersAcrossDeferredEventFields() {
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

        assertTrue(event.argumentAt(0) instanceof List<?>);
        assertEquals("[shared reference]", event.argumentAt(1));
        assertEquals("[shared reference]", event.attributes().get("shared"));
        assertEquals(true, event.attributes().get("logyard.capture.truncated"));
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
    void cutsSharedThrowableReferencesAndBoundsRenderedTextWithoutArguments() {
        IllegalStateException shared = new IllegalStateException("shared");
        RuntimeException parent = new RuntimeException("parent");
        parent.addSuppressed(shared);
        parent.addSuppressed(shared);

        LogEvent event = event(null, AttributeSet.EMPTY, parent);
        ExceptionSnapshot snapshot = event.exception();

        assertEquals("shared", snapshot.suppressed().get(0).message());
        assertEquals("[shared exception reference]", snapshot.suppressed().get(1).message());
        assertTrue(snapshot.truncated());
        String rendered = MessageFormatter.format("x".repeat(CaptureLimits.MAX_TEXT_CHARS + 1_000), null);
        assertEquals(CaptureLimits.MAX_TEXT_CHARS, rendered.length());
        assertTrue(rendered.endsWith("…"));
    }

    @Test
    void capsActualTreeDepthEvenWhenEveryLevelReusesThePreviousValue() {
        Object nested = List.of("leaf");
        for (int depth = 0; depth < 1_000; depth++) {
            nested = List.of(nested, nested);
        }

        LogEvent event = event(new Object[] {nested}, AttributeSet.EMPTY, null);

        assertTrue(capturedDepth(event.argumentAt(0)) <= CaptureLimits.MAX_NESTING_DEPTH + 1);
        assertEquals(true, event.attributes().get("logyard.capture.truncated"));
    }

    @Test
    void reservesMessageAndExceptionDiagnosticsBeforeLargePayloads() {
        IllegalStateException failure = new IllegalStateException("failure-".repeat(4_096));
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("example.OrderService", "submit", "OrderService.java", 42)
        });
        String payload = "p".repeat(CaptureLimits.MAX_TEXT_CHARS);
        LogEvent event = new LogEvent(
                1L,
                2L,
                Level.ERROR,
                "test.capture",
                null,
                "failed {} {} {} {}",
                new Object[] {payload, payload, payload, payload},
                AttributeSet.EMPTY,
                failure,
                3L,
                "main");

        assertTrue(!event.renderedMessage().isEmpty());
        assertTrue(!event.exception().type().isEmpty());
        assertTrue(!event.exception().frames().isEmpty());
        assertEquals("example.OrderService", event.exception().frames().getFirst().getClassName());
        assertTrue(event.exception().truncated());
        assertEquals(true, event.attributes().get("logyard.capture.truncated"));
    }

    @Test
    void capturesEnumsAndArbitraryPrecisionNumbersWithoutUnsafeExpansion() {
        EventCaptureTestValues.HostileEnum.TO_STRING_CALLS.set(0);
        BigDecimal compactExponent = new BigDecimal(BigInteger.ONE, -1_000_000);
        BigInteger oversizedInteger = BigInteger.ONE.shiftLeft(100_000);
        LogEvent event = event(
                new Object[] {EventCaptureTestValues.HostileEnum.VALUE, compactExponent, oversizedInteger},
                AttributeSet.EMPTY,
                null);

        assertEquals("VALUE", event.argumentAt(0));
        assertEquals(0, EventCaptureTestValues.HostileEnum.TO_STRING_CALLS.get());
        assertTrue(event.argumentAt(1) instanceof BigDecimal);
        assertTrue(event.argumentAt(1).toString().length() < 32);
        assertTrue(event.argumentAt(2).toString().startsWith("[numeric value omitted:"));
        assertEquals(true, event.attributes().get("logyard.capture.truncated"));
    }

    @Test
    void reportsWhenAnExactNumberDoesNotFitTheRemainingPayloadPartition() {
        String payload = "p".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS - 64);
        BigInteger exactButTooLarge = new BigInteger("9".repeat(128));

        LogEvent event = event(new Object[] {payload, exactButTooLarge}, AttributeSet.EMPTY, null);

        assertTrue(event.argumentAt(1).toString().startsWith("[numeric value omitted:"));
        assertEquals(true, event.attributes().get("logyard.capture.truncated"));
    }

    @Test
    void rendersTenThousandLevelsWithoutRecursingPastTheConfiguredDepth() {
        Object nested = List.of("leaf");
        for (int depth = 0; depth < 10_000; depth++) {
            nested = List.of(nested);
        }

        String rendered = MessageFormatter.safeToString(nested);

        assertTrue(rendered.contains("[maximum nesting depth reached]"));
        assertTrue(rendered.length() <= CaptureLimits.MAX_TEXT_CHARS);
    }

    @Test
    void reportsLazyMessageTruncationSeparatelyFromIngressCapture() {
        List<List<Long>> denseNumbers = new ArrayList<>();
        for (int row = 0; row < 31; row++) {
            denseNumbers.add(java.util.Collections.nCopies(
                    CaptureLimits.MAX_COLLECTION_ELEMENTS,
                    Long.MAX_VALUE));
        }
        LogEvent event = new LogEvent(
                1L,
                2L,
                Level.INFO,
                "test.capture",
                null,
                "{}",
                new Object[] {denseNumbers},
                AttributeSet.EMPTY,
                null,
                3L,
                "main");

        assertEquals(CaptureLimits.MAX_RENDERED_MESSAGE_CHARS, event.renderedMessage().length());
        assertTrue(event.renderedMessageTruncated());
    }

    @Test
    void reappliesTheOriginalPayloadAllowanceAfterProcessorEnrichment() {
        LogEvent event = event(
                new Object[] {"argument-".repeat(1_000)},
                AttributeSet.of("original", "value"),
                null);
        AttributeSet enrichment = AttributeSet.builder()
                .put("processor.first", "x".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS))
                .put("processor.second", "y".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS))
                .build();

        LogEvent enriched = event.enrich(null, null, enrichment);

        assertTrue(retainedCharacters(enriched.attributes()) <= CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);
        assertEquals(true, enriched.attributes().get("logyard.capture.truncated"));
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
                null,
                new Object[] {99},
                false);
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

}
