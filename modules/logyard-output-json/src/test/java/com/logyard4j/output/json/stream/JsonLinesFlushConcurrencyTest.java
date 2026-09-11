package com.logyard4j.output.json.stream;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.output.json.testing.FlushWorkerAssertions;
import com.logyard4j.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.api.Test;

import java.io.Writer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonLinesFlushConcurrencyTest {
    @Test
    void twoBlockedStreamsDoNotStarveAnIndependentFastStream() throws Exception {
        CountDownLatch blocked = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fast = new CountDownLatch(1);
        GateWriter firstWriter = new GateWriter(blocked, release);
        GateWriter secondWriter = new GateWriter(blocked, release);
        GateWriter fastWriter = new GateWriter(fast, new CountDownLatch(0));
        JsonLinesSink first = sink(firstWriter);
        JsonLinesSink second = sink(secondWriter);
        JsonLinesSink third = sink(fastWriter);
        try {
            first.accept(event("first"));
            second.accept(event("second"));
            assertTrue(blocked.await(2L, TimeUnit.SECONDS), "two stream flushes did not block");

            third.accept(event("fast"));
            assertTrue(fast.await(2L, TimeUnit.SECONDS), "blocked streams starved the fast stream");
        } finally {
            release.countDown();
            first.close();
            second.close();
            third.close();
        }
        FlushWorkerAssertions.awaitNoFlushWorkers();
    }

    @Test
    void explicitFlushCancelsTheDueTaskWaitingBehindIt() throws Exception {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GateWriter writer = new GateWriter(entered, release);
        JsonLinesSink sink = new JsonLinesSink(
                writer, LogEvent::messageTemplate, Duration.ofSeconds(1L), true, scheduler);
        ExecutorService flusher = Executors.newSingleThreadExecutor();
        try {
            sink.accept(event("record"));
            Future<?> explicit = flusher.submit(sink::flush);
            assertTrue(entered.await(2L, TimeUnit.SECONDS));

            scheduler.runNext();
            release.countDown();
            explicit.get(2L, TimeUnit.SECONDS);
            sink.close();

            assertEquals(1, writer.flushes.get());
            assertEquals(1, writer.maximumConcurrentFlushes.get());
            assertEquals(1, writer.closes.get());
        } finally {
            release.countDown();
            flusher.shutdownNow();
            sink.close();
        }
    }

    @Test
    void closeWaitsForAnInFlightTimedFlushBeforeClosingTheWriter() throws Exception {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        GateWriter writer = new GateWriter(entered, release);
        JsonLinesSink sink = new JsonLinesSink(
                writer, LogEvent::messageTemplate, Duration.ofSeconds(1L), true, scheduler);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            sink.accept(event("record"));
            scheduler.runNext();
            assertTrue(entered.await(2L, TimeUnit.SECONDS));

            Future<?> closed = closer.submit(() -> {
                closeStarted.countDown();
                sink.close();
            });
            assertTrue(closeStarted.await(2L, TimeUnit.SECONDS));
            assertFalse(closed.isDone());
            release.countDown();
            closed.get(2L, TimeUnit.SECONDS);

            assertEquals(1, writer.flushes.get());
            assertEquals(1, writer.closes.get());
        } finally {
            release.countDown();
            closer.shutdownNow();
            sink.close();
        }
    }

    private static JsonLinesSink sink(Writer writer) {
        return new JsonLinesSink(writer, LogEvent::messageTemplate, Duration.ofMillis(1L), true);
    }

    private static LogEvent event(String message) {
        return new LogEvent(0L, 0L, Level.INFO, "test.Logger", "test", message, null, AttributeSet.EMPTY, null, 1L, "test");
    }

    private static final class GateWriter extends Writer {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicInteger flushes = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger activeFlushes = new AtomicInteger();
        private final AtomicInteger maximumConcurrentFlushes = new AtomicInteger();

        private GateWriter(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public void write(char[] characters, int offset, int length) {
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
            maximumConcurrentFlushes.accumulateAndGet(activeFlushes.incrementAndGet(), Math::max);
            entered.countDown();
            awaitUninterruptibly(release);
            activeFlushes.decrementAndGet();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            boolean interrupted = false;
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
