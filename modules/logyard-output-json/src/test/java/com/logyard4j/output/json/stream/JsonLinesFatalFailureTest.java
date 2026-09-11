package com.logyard4j.output.json.stream;

import com.logyard4j.api.Level;
import com.logyard4j.api.diagnostics.HealthStatus;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.api.Test;

import java.io.Writer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JsonLinesFatalFailureTest {
    @Test
    void synchronousFatalWriterFailureIsStickyBeforeExactRethrow() {
        LinkageError failure = new LinkageError("fatal write failure");
        FatalWriter writer = FatalWriter.duringWrite(failure);
        JsonLinesSink sink = new JsonLinesSink(writer, LogEvent::messageTemplate, Duration.ofSeconds(1L), false);

        assertSame(failure, assertThrows(LinkageError.class, () -> sink.accept(event())));

        assertStickyFailure(sink, writer, failure, 1, 0);
        sink.close();
    }

    @Test
    void scheduledFatalWriterFailureIsStickyBeforeExactRethrow() throws Exception {
        LinkageError failure = new LinkageError("fatal flush failure");
        FatalWriter writer = FatalWriter.duringFlush(failure);
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        JsonLinesSink sink = new JsonLinesSink(
                writer, LogEvent::messageTemplate, Duration.ofSeconds(1L), false, scheduler);
        CountDownLatch rethrown = new CountDownLatch(1);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();
        sink.accept(event());

        Thread.setDefaultUncaughtExceptionHandler((thread, thrown) -> {
            if (thread.getName().startsWith("logyard-json-flush-worker-")) {
                uncaught.set(thrown);
                rethrown.countDown();
            } else if (originalHandler != null) {
                originalHandler.uncaughtException(thread, thrown);
            }
        });
        try {
            scheduler.runNext();
            awaitFailed(sink);
            if (!rethrown.await(2L, TimeUnit.SECONDS)) {
                throw new AssertionError("scheduled fatal failure was not rethrown from the flush worker");
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalHandler);
        }

        assertSame(failure, uncaught.get());
        assertStickyFailure(sink, writer, failure, 2, 1);
        assertEquals(0, scheduler.pendingCount());
        sink.close();
    }

    private static void assertStickyFailure(
            JsonLinesSink sink, FatalWriter writer, LinkageError failure, int expectedWrites, int expectedFlushes) {
        assertEquals(HealthStatus.FAILED, sink.health("json").status());
        assertEquals(LinkageError.class.getName(), sink.health("json").details().get("writer_failure"));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> sink.accept(event())).getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, sink::flush).getCause());
        assertEquals(expectedWrites, writer.writes.get());
        assertEquals(expectedFlushes, writer.flushes.get());
    }

    private static void awaitFailed(JsonLinesSink sink) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (sink.health("json").status() != HealthStatus.FAILED) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("scheduled fatal stream failure was not published");
            }
            Thread.onSpinWait();
        }
    }

    private static LogEvent event() {
        return new LogEvent(
                0L, 0L, Level.INFO, "test.Logger", "test", "record", null, AttributeSet.EMPTY, null, 1L, "test");
    }

    private static final class FatalWriter extends Writer {
        private final LinkageError failure;
        private final boolean failWrite;
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicInteger flushes = new AtomicInteger();

        private FatalWriter(LinkageError failure, boolean failWrite) {
            this.failure = failure;
            this.failWrite = failWrite;
        }

        static FatalWriter duringWrite(LinkageError failure) {
            return new FatalWriter(failure, true);
        }

        static FatalWriter duringFlush(LinkageError failure) {
            return new FatalWriter(failure, false);
        }

        @Override
        public void write(char[] characters, int offset, int length) {
            writes.incrementAndGet();
            if (failWrite) {
                throw failure;
            }
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
            if (!failWrite) {
                throw failure;
            }
        }

        @Override
        public void close() {
        }
    }
}
