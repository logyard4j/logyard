package com.logyard4j.logyard.output.json.stream;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.diagnostics.HealthStatus;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JsonLinesSinkFailureTest {
    @Test
    void failureBeforeProgressIsSticky() {
        FailOnceWriter writer = FailOnceWriter.duringWrite(0);
        JsonLinesSink sink = sink(writer, Duration.ofSeconds(1L), false);

        assertThrows(UncheckedIOException.class, () -> sink.accept(event("FIRST")));

        assertFailedAndRejectsLater(sink, writer, IOException.class);
        assertEquals("", writer.contents());
        sink.close();
        assertEquals(0, writer.flushes.get());
    }

    @Test
    void uncheckedFailureBeforeProgressIsSticky() {
        UncheckedIOException failure = new UncheckedIOException(new IOException("unchecked"));
        FailOnceWriter writer = FailOnceWriter.duringWrite(0, failure);
        JsonLinesSink sink = sink(writer, Duration.ofSeconds(1L), false);

        assertSame(failure, assertThrows(UncheckedIOException.class, () -> sink.accept(event("FIRST"))));

        assertFailedAndRejectsLater(sink, writer, UncheckedIOException.class);
        sink.close();
    }

    @Test
    void partialPayloadFailureIsSticky() {
        FailOnceWriter writer = FailOnceWriter.duringWrite(1);
        JsonLinesSink sink = sink(writer, Duration.ofSeconds(1L), false);

        assertThrows(UncheckedIOException.class, () -> sink.accept(event("FIRST")));

        assertFailedAndRejectsLater(sink, writer, IOException.class);
        assertEquals("F", writer.contents());
        sink.close();
        assertEquals(0, writer.flushes.get());
    }

    @Test
    void uncheckedFailureAfterPartialProgressIsSticky() {
        IllegalStateException failure = new IllegalStateException("uncertain progress");
        FailOnceWriter writer = FailOnceWriter.duringWrite(1, failure);
        JsonLinesSink sink = sink(writer, Duration.ofSeconds(1L), false);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> sink.accept(event("FIRST"))));

        assertFailedAndRejectsLater(sink, writer, IllegalStateException.class);
        assertEquals("F", writer.contents());
        sink.close();
    }

    @Test
    void assertionFailureAfterPartialProgressIsSticky() {
        AssertionError failure = new AssertionError("uncertain progress");
        FailOnceWriter writer = FailOnceWriter.duringWrite(1, failure);
        JsonLinesSink sink = sink(writer, Duration.ofSeconds(1L), false);

        assertSame(failure, assertThrows(AssertionError.class, () -> sink.accept(event("FIRST"))));

        assertFailedAndRejectsLater(sink, writer, AssertionError.class);
        assertEquals("F", writer.contents());
        sink.close();
    }

    @Test
    void newlineFailureIsSticky() {
        FailOnceWriter writer = FailOnceWriter.duringWrite("FIRST".length());
        JsonLinesSink sink = sink(writer, Duration.ofSeconds(1L), false);

        assertThrows(UncheckedIOException.class, () -> sink.accept(event("FIRST")));

        assertFailedAndRejectsLater(sink, writer, IOException.class);
        assertEquals("FIRST", writer.contents());
        sink.close();
    }

    @Test
    void explicitFlushFailureIsStickyAndCancelsTheTimer() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        FailOnceWriter writer = FailOnceWriter.duringFlush();
        JsonLinesSink sink = new JsonLinesSink(
                writer, LogEvent::messageTemplate, Duration.ofSeconds(1L), false, scheduler);
        sink.accept(event("FIRST"));

        assertThrows(UncheckedIOException.class, sink::flush);

        assertFailedAndRejectsLater(sink, writer, IOException.class);
        assertEquals(0, scheduler.pendingCount());
        sink.close();
        assertEquals(1, writer.flushes.get());
    }

    @Test
    void scheduledFlushFailureIsStickyAndVisibleInHealth() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        FailOnceWriter writer = FailOnceWriter.duringFlush();
        JsonLinesSink sink = new JsonLinesSink(
                writer, LogEvent::messageTemplate, Duration.ofSeconds(1L), false, scheduler);
        sink.accept(event("FIRST"));

        scheduler.runNext();
        awaitFailed(sink);

        assertFailedAndRejectsLater(sink, writer, IOException.class);
        assertEquals(1, scheduler.scheduledCount());
        sink.close();
        assertEquals(1, writer.flushes.get());
    }

    @Test
    void cleanupFailureIsSuppressedOnTheFirstTransportFailure() {
        FailOnceWriter writer = FailOnceWriter.duringWrite(1);
        writer.closeFailure = new IOException("cleanup failed");
        JsonLinesSink sink = sink(writer, Duration.ofSeconds(1L), true);
        UncheckedIOException writeFailure = assertThrows(
                UncheckedIOException.class, () -> sink.accept(event("FIRST")));

        UncheckedIOException closeFailure = assertThrows(UncheckedIOException.class, sink::close);

        assertSame(writeFailure.getCause(), closeFailure.getCause());
        assertEquals(1, closeFailure.getCause().getSuppressed().length);
        assertSame(writer.closeFailure, closeFailure.getCause().getSuppressed()[0]);
        assertEquals(1, writer.closes.get());
        sink.close();
        assertEquals(1, writer.closes.get());
        assertEquals(HealthStatus.FAILED, sink.health("json").status());
    }

    @Test
    void encoderFailureRemainsEventLocal() {
        FailOnceWriter writer = FailOnceWriter.healthy();
        JsonLinesSink sink = new JsonLinesSink(
                writer, event -> {
                    throw new IllegalArgumentException("bad event");
                }, Duration.ZERO, false);

        assertThrows(IllegalArgumentException.class, () -> sink.accept(event("FIRST")));

        assertEquals(HealthStatus.HEALTHY, sink.health("json").status());
        assertEquals(0, writer.writeCalls.get());
        sink.close();
    }

    private static JsonLinesSink sink(FailOnceWriter writer, Duration interval, boolean closeWriter) {
        return new JsonLinesSink(writer, LogEvent::messageTemplate, interval, closeWriter);
    }

    private static void assertFailedAndRejectsLater(
            JsonLinesSink sink,
            FailOnceWriter writer,
            Class<? extends Throwable> failureType) {
        var health = sink.health("json");
        assertEquals(HealthStatus.FAILED, health.status());
        assertEquals(failureType.getName(), health.details().get("writer_failure"));
        int writesAfterFailure = writer.writeCalls.get();
        IllegalStateException rejection = assertThrows(
                IllegalStateException.class, () -> sink.accept(event("SECOND")));
        assertEquals(failureType, rejection.getCause().getClass());
        IllegalStateException flushRejection = assertThrows(IllegalStateException.class, sink::flush);
        assertSame(rejection.getCause(), flushRejection.getCause());
        assertEquals(writesAfterFailure, writer.writeCalls.get());
    }

    private static void awaitFailed(JsonLinesSink sink) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (sink.health("json").status() != HealthStatus.FAILED) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("scheduled stream failure was not published");
            }
            Thread.onSpinWait();
        }
    }

    private static LogEvent event(String message) {
        return new LogEvent(0L, 0L, Level.INFO, "test.Logger", "test", message, null, AttributeSet.EMPTY, null, 1L, "test");
    }

    private static final class FailOnceWriter extends Writer {
        private final StringBuilder written = new StringBuilder();
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final AtomicInteger flushes = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private int charactersBeforeFailure;
        private boolean failWrite;
        private boolean failFlush;
        private Throwable writeFailure;
        private Throwable flushFailure;
        private Throwable closeFailure;

        private FailOnceWriter(
                int charactersBeforeFailure,
                boolean failWrite,
                boolean failFlush,
                Throwable writeFailure,
                Throwable flushFailure) {
            this.charactersBeforeFailure = charactersBeforeFailure;
            this.failWrite = failWrite;
            this.failFlush = failFlush;
            this.writeFailure = writeFailure;
            this.flushFailure = flushFailure;
        }

        static FailOnceWriter healthy() {
            return new FailOnceWriter(0, false, false, null, null);
        }

        static FailOnceWriter duringWrite(int charactersBeforeFailure) {
            return duringWrite(charactersBeforeFailure, new IOException("injected write failure"));
        }

        static FailOnceWriter duringWrite(int charactersBeforeFailure, Throwable failure) {
            return new FailOnceWriter(charactersBeforeFailure, true, false, failure, null);
        }

        static FailOnceWriter duringFlush() {
            return new FailOnceWriter(0, false, true, null, new IOException("injected flush failure"));
        }

        @Override
        public void write(char[] characters, int offset, int length) throws IOException {
            writeCalls.incrementAndGet();
            if (!failWrite) {
                written.append(characters, offset, length);
                return;
            }
            int copied = Math.min(length, charactersBeforeFailure);
            written.append(characters, offset, copied);
            charactersBeforeFailure -= copied;
            if (copied < length) {
                failWrite = false;
                throwFailure(writeFailure);
            }
        }

        @Override
        public void flush() throws IOException {
            flushes.incrementAndGet();
            if (failFlush) {
                failFlush = false;
                throwFailure(flushFailure);
            }
        }

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
            if (closeFailure != null) {
                throwFailure(closeFailure);
            }
        }

        String contents() {
            return written.toString();
        }

        private static void throwFailure(Throwable failure) throws IOException {
            if (failure instanceof IOException checked) {
                throw checked;
            }
            if (failure instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IOException(failure);
        }
    }
}
