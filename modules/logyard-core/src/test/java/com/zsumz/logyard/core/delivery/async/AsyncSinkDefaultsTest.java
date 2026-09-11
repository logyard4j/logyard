package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncSinkDefaultsTest {
    @Test
    void saturationBoundsAdmissionForEverySeverityWithoutCallerOutput() throws Exception {
        exerciseDefaultAdmission(false);
    }

    @Test
    void closedOutputDropsEverySeverityWithoutWritingToStalledStderr() throws Exception {
        exerciseDefaultAdmission(true);
    }

    private static void exerciseDefaultAdmission(boolean closeFirst) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        EventSink delegate = ignored -> {
            entered.countDown();
            await(release);
        };
        AsyncSink sink = new AsyncSink("defaults", delegate, 16,
                new OverflowPolicy(Map.of()), Duration.ofSeconds(2));
        PrintStream previous = System.err;
        PrintStream stalled = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) {
                writes.incrementAndGet();
                await(release);
            }
        });
        Thread publisher = new Thread(() -> {
            try {
                for (Level level : Level.values()) {
                    long started = System.nanoTime();
                    sink.accept(event(level));
                    if (!closeFirst) {
                        long minimumWait = new OverflowPolicy(Map.of()).ruleFor(level).waitDuration().toNanos();
                        assertTrue(System.nanoTime() - started >= minimumWait, "default wait was bypassed for " + level);
                    }
                }
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "default-admission-test");
        publisher.setDaemon(true);
        try {
            if (closeFirst) {
                sink.close();
            } else {
                sink.accept(event(Level.INFO));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                for (int index = 0; index < sink.capacity(); index++) {
                    sink.accept(event(Level.INFO));
                }
            }
            System.setErr(stalled);
            publisher.start();
            publisher.join(1_000);
            assertFalse(publisher.isAlive(), "default admission waited for stderr");
            assertNull(failure.get());
            assertEquals(0, writes.get());
            for (Level level : Level.values()) {
                assertEquals(1L, sink.dropped(level));
            }
            assertEquals(0L, sink.emergencyFallbacks());
            assertEquals(0L, sink.synchronousFallbacks());
        } finally {
            release.countDown();
            publisher.join(2_000);
            System.setErr(previous);
            sink.close();
            stalled.close();
        }
    }

    private static LogEvent event(Level level) {
        return new LogEvent(1, 2, level, "test", null, "message", null,
                AttributeSet.EMPTY, null, 1, "main");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("test output was not released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
