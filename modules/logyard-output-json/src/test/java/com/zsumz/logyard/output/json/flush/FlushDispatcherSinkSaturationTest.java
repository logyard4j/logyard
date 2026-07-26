package com.zsumz.logyard.output.json.flush;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.output.json.stream.JsonLinesSink;
import com.zsumz.logyard.output.json.testing.FlushWorkerAssertions;
import org.junit.jupiter.api.Test;

import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlushDispatcherSinkSaturationTest {
    @Test
    void dirtyActualSinkFlushesAfterSharedWorkerCapacityReturns() throws Exception {
        CountDownLatch blocked = new CountDownLatch(BoundedElasticFlushDispatcher.MAXIMUM_WORKERS);
        CountDownLatch release = new CountDownLatch(1);
        List<JsonLinesSink> blockingSinks = new ArrayList<>(BoundedElasticFlushDispatcher.MAXIMUM_WORKERS);
        JsonLinesSink waitingSink = null;
        CountDownLatch waitingFlush = new CountDownLatch(1);
        try {
            for (int index = 0; index < BoundedElasticFlushDispatcher.MAXIMUM_WORKERS; index++) {
                JsonLinesSink sink = sink(new BlockingWriter(blocked, release));
                blockingSinks.add(sink);
                sink.accept(event("blocked-" + index));
            }
            assertTrue(blocked.await(5L, TimeUnit.SECONDS), "shared flush workers did not reach their configured bound");

            waitingSink = sink(new SignalWriter(waitingFlush));
            waitingSink.accept(event("waiting"));
            assertFalse(waitingFlush.await(1_500L, TimeUnit.MILLISECONDS), "saturated worker pool unexpectedly accepted flush I/O");

            release.countDown();
            assertTrue(waitingFlush.await(3L, TimeUnit.SECONDS), "dirty sink did not flush after worker capacity returned");
        } finally {
            release.countDown();
            if (waitingSink != null) {
                waitingSink.close();
            }
            blockingSinks.forEach(JsonLinesSink::close);
        }
        FlushWorkerAssertions.awaitNoFlushWorkers();
    }

    private static JsonLinesSink sink(Writer writer) {
        return new JsonLinesSink(writer, LogEvent::messageTemplate, Duration.ofMillis(1L), false);
    }

    private static LogEvent event(String message) {
        return new LogEvent(0L, 0L, Level.INFO, "test.Logger", "test", message, null, AttributeSet.EMPTY, null, 1L, "test");
    }

    private static final class BlockingWriter extends Writer {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private BlockingWriter(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public void write(char[] characters, int offset, int length) {
        }

        @Override
        public void flush() {
            entered.countDown();
            awaitUninterruptibly(release);
        }

        @Override
        public void close() {
        }
    }

    private static final class SignalWriter extends Writer {
        private final CountDownLatch flushed;

        private SignalWriter(CountDownLatch flushed) {
            this.flushed = flushed;
        }

        @Override
        public void write(char[] characters, int offset, int length) {
        }

        @Override
        public void flush() {
            flushed.countDown();
        }

        @Override
        public void close() {
        }
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
