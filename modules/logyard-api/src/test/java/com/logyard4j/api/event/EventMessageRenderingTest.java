package com.logyard4j.api.event;

import com.logyard4j.api.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventMessageRenderingTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "accepted", "{}", "\\{}", "\\\\{}", "a😀b", "\uD800", "\uDC00"})
    void noArgumentsPreserveLiteralTextAndRepeatedRendering(String template) {
        LogEvent event = event(template, null);
        String expected = template == null ? "null" : template;
        assertEquals(expected, event.renderedMessage());
        assertFalse(event.renderedMessageTruncated());
        assertSame(event.renderedMessage(), event.renderedMessage());
        assertSame(event.renderedMessage(), event.withAttributes(AttributeSet.of("key", 42)).renderedMessage());
        assertEquals("replacement \\{}", event.withMessageTemplate("replacement \\{}").renderedMessage());
    }

    @Test
    void templateCaptureBoundsRemainDistinctFromRenderingBounds() {
        String template = "x".repeat(CaptureLimits.MAX_EVENT_TEMPLATE_CHARS - 2) + "😀tail";
        LogEvent event = event(template, new Object[0]);
        assertEquals("x".repeat(CaptureLimits.MAX_EVENT_TEMPLATE_CHARS - 2) + "…", event.renderedMessage());
        assertEquals(Boolean.TRUE, event.attributes().get(SystemAttributes.CAPTURE_TRUNCATED));
        assertFalse(event.renderedMessageTruncated());
        assertEquals(event.messageTemplate(), event.renderedMessage());
        LogEvent replacement = event("short", null).withMessageTemplate(template);
        assertEquals(event.renderedMessage(), replacement.renderedMessage());
        assertFalse(replacement.renderedMessageTruncated());
        assertEquals(Boolean.TRUE, replacement.attributes().get(SystemAttributes.CAPTURE_TRUNCATED));
    }

    @Test
    void nullTemplateStillCapturesDeferredArgumentsBeforeAnyReplacement() {
        List<String> mutable = new ArrayList<>(List.of("before"));
        AtomicInteger calls = new AtomicInteger();
        Thread caller = Thread.currentThread();
        LogEvent event = LogEvent.captureDeferred(1, 2, Level.INFO, "test", null, null, () -> {
            assertSame(caller, Thread.currentThread());
            calls.incrementAndGet();
            return new Object[] {mutable};
        }, () -> AttributeSet.EMPTY, 1, null, 3, "caller");
        mutable.set(0, "after");
        assertEquals(1, calls.get());
        assertEquals(List.of("before"), event.argumentAt(0));
        assertEquals("null", event.renderedMessage());
        LogEvent formatted = event.withMessageTemplate("value {}");
        assertEquals("value [before]", formatted.renderedMessage());
        assertEquals("null", formatted.withMessageTemplate(null).renderedMessage());
        assertEquals("again [before]", formatted.withMessageTemplate(null)
                .withMessageTemplate("again {}").renderedMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void replacementsRecomputeRenderingTruncationFromDetachedArguments() {
        List<List<Long>> numbers = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            numbers.add(Collections.nCopies(CaptureLimits.MAX_COLLECTION_ELEMENTS, Long.MAX_VALUE));
        }
        LogEvent event = event(null, new Object[] {numbers});
        assertFalse(event.renderedMessageTruncated());
        LogEvent formatted = event.withMessageTemplate("{}");
        assertEquals(CaptureLimits.MAX_RENDERED_MESSAGE_CHARS, formatted.renderedMessage().length());
        assertTrue(formatted.renderedMessageTruncated());
        LogEvent cleared = formatted.withMessageTemplate(null);
        assertEquals("null", cleared.renderedMessage());
        assertFalse(cleared.renderedMessageTruncated());
        assertTrue(cleared.withMessageTemplate("{}").renderedMessageTruncated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"literal", "value {}"})
    void concurrentFanoutAndAttributeCopiesShareTheRenderedValue(String template) throws Exception {
        LogEvent event = event(template, template.contains("{}") ? new Object[] {List.of("before")} : null);
        List<LogEvent> copies = List.of(event, event.withAttributes(AttributeSet.of("key", 42)),
                event.withEventName("copy"), event.withMessageTemplate(new String(template)));
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                LogEvent copy = copies.get(index % copies.size());
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    assertFalse(copy.renderedMessageTruncated());
                    return copy.renderedMessage();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            String rendered = results.get(0).get(5, TimeUnit.SECONDS);
            assertEquals(template.contains("{}") ? "value [before]" : template, rendered);
            for (Future<String> result : results) assertSame(rendered, result.get(5, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static LogEvent event(String template, Object[] arguments) {
        return new LogEvent(1, 2, Level.INFO, "test", null, template, arguments,
                AttributeSet.EMPTY, null, 3, "caller");
    }
}
