package com.logyard4j.output.json.stream;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JsonLinesTimedFlushTest {
    @Test
    void sparseEventFlushesWithoutAnotherAccept() throws Exception {
        CountingWriter writer = new CountingWriter();
        try (JsonLinesSink sink = new JsonLinesSink(writer, LogEvent::messageTemplate, Duration.ofMillis(30L), false)) {
            sink.accept(event("sparse"));
            await(() -> writer.flushes.get() == 1);
        }
    }

    @Test
    void dirtyPeriodSchedulesOnceAndExplicitFlushAndCloseCancel() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        CountingWriter writer = new CountingWriter();
        JsonLinesSink sink = new JsonLinesSink(writer, LogEvent::messageTemplate, Duration.ofSeconds(1L), false, scheduler);

        sink.accept(event("one"));
        sink.accept(event("two"));
        assertEquals(1, scheduler.scheduledCount());
        assertEquals(1, scheduler.pendingCount());

        sink.flush();
        assertEquals(1, writer.flushes.get());
        assertEquals(0, scheduler.pendingCount());
        sink.accept(event("three"));
        assertEquals(2, scheduler.scheduledCount());

        sink.close();
        assertEquals(2, writer.flushes.get());
        assertEquals(0, scheduler.pendingCount());
        assertThrows(IllegalStateException.class, scheduler::runNext);
    }

    @Test
    void zeroIntervalFlushesSynchronouslyWithoutScheduling() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        CountingWriter writer = new CountingWriter();
        try (JsonLinesSink sink = new JsonLinesSink(writer, LogEvent::messageTemplate, Duration.ZERO, false, scheduler)) {
            sink.accept(event("one"));
            sink.accept(event("two"));
            assertEquals(2, writer.flushes.get());
            assertEquals(0, scheduler.scheduledCount());
        }
        assertEquals(3, writer.flushes.get());
    }

    @Test
    void directConstructorUsesTheConfigurationIntervalBound() {
        assertThrows(IllegalArgumentException.class, () -> new JsonLinesSink(
                new StringWriter(),
                LogEvent::messageTemplate,
                Duration.ofMinutes(1L).plusNanos(1L),
                false));
        try (JsonLinesSink ignored = new JsonLinesSink(
                new StringWriter(), LogEvent::messageTemplate, Duration.ofMinutes(1L), false)) {
            assertEquals(0, ignored.health("json").metrics().size());
        }
    }

    private static LogEvent event(String message) {
        return new LogEvent(0L, 0L, Level.INFO, "test.Logger", "test", message, null, AttributeSet.EMPTY, null, 1L, "test");
    }

    private static void await(Condition condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!condition.satisfied()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("timed flush did not complete before the test deadline");
            }
            Thread.sleep(10L);
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean satisfied();
    }

    private static final class CountingWriter extends StringWriter {
        private final AtomicInteger flushes = new AtomicInteger();

        @Override
        public void flush() {
            flushes.incrementAndGet();
        }
    }
}
