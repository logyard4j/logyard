package com.zsumz.logyard.output.json.file;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonFileTimedFlushTest {
    @Test
    void oneSecondSparseEventBecomesVisibleWithoutAnotherAccept() throws Exception {
        Path output = Files.createTempDirectory("logyard-sparse-flush-").resolve("events.jsonl");
        try (JsonFileSink sink = new JsonFileSink(output, event -> "{\"event\":true}", 1_024, Duration.ofSeconds(1L), false, null)) {
            sink.accept(event("sparse"));
            await(() -> Files.size(output) > 0L);
        }
        assertEquals("{\"event\":true}\n", Files.readString(output));
    }

    @Test
    void dirtyPeriodSchedulesOnceAndExplicitFlushAndCloseCancel() throws Exception {
        Path output = Files.createTempDirectory("logyard-manual-flush-").resolve("events.jsonl");
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        JsonFileSink sink = new JsonFileSink(
                output,
                LogEvent::messageTemplate,
                1_024,
                Duration.ofSeconds(1L),
                false,
                null,
                true,
                scheduler,
                BufferedFileWriter::open);

        sink.accept(event("one"));
        sink.accept(event("two"));
        assertEquals(1, scheduler.scheduledCount());
        assertEquals(1, scheduler.pendingCount());

        sink.flush();
        assertEquals(0, scheduler.pendingCount());
        sink.accept(event("three"));
        assertEquals(2, scheduler.scheduledCount());

        sink.close();
        assertEquals(0, scheduler.pendingCount());
        assertThrows(IllegalStateException.class, scheduler::runNext);
    }

    @Test
    void unusedPreparedSinkDoesNotOpenAFileOrScheduleAFlush() throws Exception {
        Path output = Files.createTempDirectory("logyard-unused-flush-").resolve("events.jsonl");
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        AtomicInteger opens = new AtomicInteger();
        try (JsonFileSink sink = new JsonFileSink(
                output,
                LogEvent::messageTemplate,
                1_024,
                Duration.ofSeconds(1L),
                false,
                null,
                false,
                scheduler,
                (path, bufferBytes, append) -> {
                    opens.incrementAndGet();
                    throw new AssertionError("unused prepared output opened its data file");
                })) {
            sink.activate();
        }

        assertEquals(0, opens.get());
        assertEquals(0, scheduler.scheduledCount());
        assertFalse(Files.exists(output));
    }

    @Test
    void scheduledFlushFailureIsTerminalAndVisibleInHealth() throws Exception {
        Path output = Files.createTempDirectory("logyard-scheduled-failure-").resolve("events.jsonl");
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        FailingFlushDataFile dataFile = new FailingFlushDataFile();
        JsonFileSink sink = new JsonFileSink(
                output,
                LogEvent::messageTemplate,
                1_024,
                Duration.ofSeconds(1L),
                false,
                null,
                true,
                scheduler,
                (path, bufferBytes, append) -> dataFile);
        sink.accept(event("first"));

        assertThrows(UncheckedIOException.class, scheduler::runNext);
        var health = sink.health("json");
        assertEquals(HealthStatus.FAILED, health.status());
        assertEquals(UncheckedIOException.class.getName(), health.details().get("writer_failure"));
        assertThrows(IllegalStateException.class, () -> sink.accept(event("later")));
        assertEquals(1, scheduler.scheduledCount());
        assertEquals(1, dataFile.flushes.get());

        sink.close();
        assertEquals(1, dataFile.flushes.get());
        assertEquals(1, dataFile.closes.get());
    }

    @Test
    void zeroIntervalFlushesEachRecordWithoutScheduling() throws Exception {
        Path output = Files.createTempDirectory("logyard-zero-flush-").resolve("events.jsonl");
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        CountingDataFile dataFile = new CountingDataFile();
        try (JsonFileSink sink = new JsonFileSink(
                output,
                LogEvent::messageTemplate,
                1_024,
                Duration.ZERO,
                false,
                null,
                true,
                scheduler,
                (path, bufferBytes, append) -> dataFile)) {
            sink.accept(event("one"));
            sink.accept(event("two"));
            assertEquals(2, dataFile.flushes.get());
            assertEquals(0, scheduler.scheduledCount());
        }
    }

    @Test
    void directConstructorRejectsIntervalsAboveOneMinuteBeforeAcquiringThePath() throws Exception {
        Path output = Files.createTempDirectory("logyard-flush-bound-").resolve("events.jsonl");
        assertThrows(IllegalArgumentException.class, () -> new JsonFileSink(
                output, LogEvent::messageTemplate, 1_024, Duration.ofMinutes(1L).plusNanos(1L), false, null));
        try (JsonFileSink ignored = new JsonFileSink(
                output, LogEvent::messageTemplate, 1_024, Duration.ofMinutes(1L), false, null)) {
            assertTrue(ignored.health("json").status().ready());
        }
    }

    private static LogEvent event(String message) {
        return new LogEvent(0L, 0L, Level.INFO, "test.Logger", "test", message, null, AttributeSet.EMPTY, null, 1L, "test");
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (!condition.satisfied()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("timed flush did not complete before the test deadline");
            }
            Thread.sleep(10L);
        }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean satisfied() throws Exception;
    }

    private static class CountingDataFile implements ActiveDataFile {
        final AtomicInteger flushes = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        private long bytes;

        @Override
        public long logicalBytes() {
            return bytes;
        }

        @Override
        public void write(byte[] record, byte terminator) {
            bytes += record.length + 1L;
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private static final class FailingFlushDataFile extends CountingDataFile {
        @Override
        public void flush() {
            flushes.incrementAndGet();
            throw new UncheckedIOException("injected flush failure", new IOException("injected flush failure"));
        }
    }
}
