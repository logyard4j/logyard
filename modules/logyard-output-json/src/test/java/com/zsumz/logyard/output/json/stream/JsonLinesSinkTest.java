package com.zsumz.logyard.output.json.stream;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonLinesSinkTest {
    @Test
    void customEncoderCanInvokeAcceptFlushAndCloseAcrossThreadsWithoutDeadlock() {
        StringWriter writer = new StringWriter();
        ExecutorService callbacks = Executors.newSingleThreadExecutor();
        AtomicReference<JsonLinesSink> sinkReference = new AtomicReference<>();
        AtomicBoolean outer = new AtomicBoolean(true);
        EventEncoder encoder = event -> {
            if (outer.compareAndSet(true, false)) {
                invoke(callbacks, () -> sinkReference.get().accept(event("nested")));
                invoke(callbacks, () -> sinkReference.get().flush());
                invoke(callbacks, () -> sinkReference.get().close());
            }
            return event.messageTemplate();
        };
        JsonLinesSink sink = new JsonLinesSink(writer, encoder, Duration.ZERO, false);
        sinkReference.set(sink);
        try {
            assertThrows(IllegalStateException.class, () -> sink.accept(event("outer")));
        } finally {
            callbacks.shutdownNow();
        }
        assertEquals("nested\n", writer.toString());
    }

    @Test
    void flushCoversTransportBytesButDoesNotWaitForAnAcceptStillEncoding() throws Exception {
        TrackingWriter writer = new TrackingWriter();
        CountDownLatch encodingStarted = new CountDownLatch(1);
        CountDownLatch allowEncoding = new CountDownLatch(1);
        JsonLinesSink sink = new JsonLinesSink(writer, event -> {
            encodingStarted.countDown();
            await(allowEncoding);
            return event.messageTemplate();
        }, Duration.ofMinutes(1L), false);
        ExecutorService publisher = Executors.newSingleThreadExecutor();
        try {
            Future<?> accepted = publisher.submit(() -> sink.accept(event("pending")));
            assertTrue(encodingStarted.await(1L, TimeUnit.SECONDS));

            sink.flush();

            assertEquals(1, writer.flushes.get());
            assertEquals("", writer.toString());
            allowEncoding.countDown();
            accepted.get(2L, TimeUnit.SECONDS);
            sink.flush();
            assertEquals("pending\n", writer.toString());
        } finally {
            allowEncoding.countDown();
            sink.close();
            publisher.shutdownNow();
        }
    }

    private static LogEvent event(String message) {
        return new LogEvent(0L, 0L, Level.INFO, "test.Logger", "test", message, null, AttributeSet.EMPTY, null, 1L, "test");
    }

    private static void invoke(ExecutorService executor, Runnable operation) {
        try {
            executor.submit(operation).get(2L, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("cross-thread sink operation did not complete", failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("encoding barrier interrupted", interrupted);
        }
    }

    private static final class TrackingWriter extends StringWriter {
        private final AtomicInteger flushes = new AtomicInteger();

        @Override
        public void flush() {
            flushes.incrementAndGet();
        }
    }
}
