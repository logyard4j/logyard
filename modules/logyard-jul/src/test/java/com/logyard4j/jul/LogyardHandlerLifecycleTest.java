package com.logyard4j.jul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.Level;
import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.routing.RouteDefinition;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.core.runtime.RuntimePlan;
import com.logyard4j.runtime.adapter.AdapterRuntimeAccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.ErrorManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Pins the handler's admission, reentrancy, and failure-isolation boundaries. */
final class LogyardHandlerLifecycleTest {
    @Test
    void aFilterThatLogsThroughItsOwnHandlerCannotReenterAdmission() {
        AtomicInteger calls = new AtomicInteger();
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink)) {
            LogyardHandler handler = new LogyardHandler(runtime);
            handler.setFilter(record -> {
                if (calls.incrementAndGet() < 4) {
                    handler.publish(new LogRecord(java.util.logging.Level.INFO, "from filter"));
                }
                return true;
            });
            handler.publish(new LogRecord(java.util.logging.Level.INFO, "outer"));
            handler.close();
        }
        assertEquals(1, calls.get());
        assertEquals(1, sink.events.size());
        assertEquals("outer", sink.events.getFirst().renderedMessage());
    }

    @Test
    void stopsASinkThatLogsBackThroughTheSameHandlerInsteadOfRecursing() {
        AtomicInteger depth = new AtomicInteger();
        List<LogEvent> captured = new ArrayList<>();
        Logger logger = Logger.getLogger("jul.reentry." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(java.util.logging.Level.ALL);
        EventSink reentrant = event -> {
            captured.add(event);
            if (depth.incrementAndGet() < 4) {
                logger.info("reentrant emission " + depth.get());
            }
        };
        try (DefaultLogyardRuntime runtime = runtime(reentrant)) {
            LogyardHandler handler = new LogyardHandler(runtime);
            logger.addHandler(handler);
            try {
                logger.info("outer");
            } finally {
                logger.removeHandler(handler);
            }
        }

        assertEquals(1, captured.size(), "the reentry guard must swallow the nested emission");
        assertEquals("outer", captured.getFirst().messageTemplate());
    }

    @Test
    void rejectsPublicationsQuietlyAfterCloseAndToleratesRepeatedClose() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink)) {
            LogyardHandler handler = new LogyardHandler(runtime);
            handler.publish(new LogRecord(java.util.logging.Level.INFO, "before close"));
            handler.close();
            handler.publish(new LogRecord(java.util.logging.Level.INFO, "after close"));
            handler.flush();
            handler.close();
            handler.publish(new LogRecord(java.util.logging.Level.SEVERE, "still after close"));
        }

        assertEquals(1, sink.events.size());
        assertEquals("before close", sink.events.getFirst().messageTemplate());
    }

    @Test
    void leavesTheErrorManagerUntouchedWhenTheRuntimeAlreadyIsolatedAnOutputFailure() {
        RecordingErrorManager errors = new RecordingErrorManager();
        EventSink failing = event -> {
            throw new IllegalStateException("sink rejected the event");
        };
        try (DefaultLogyardRuntime runtime = runtime(failing)) {
            LogyardHandler handler = new LogyardHandler(runtime);
            handler.setErrorManager(errors);
            handler.publish(new LogRecord(java.util.logging.Level.INFO, "doomed"));
        }

        assertTrue(errors.codes.isEmpty(),
                "an output failure is isolated by the runtime and never re-reported to JUL");
    }

    @Test
    void reportsAWriteFailureWhenTheRuntimeAccessItselfFails() {
        RecordingErrorManager errors = new RecordingErrorManager();
        LogyardHandler handler = new LogyardHandler(new ThrowingOnFlush());
        handler.setErrorManager(errors);

        handler.publish(new LogRecord(java.util.logging.Level.INFO, "unroutable"));

        assertEquals(1, errors.codes.size());
        assertEquals(ErrorManager.WRITE_FAILURE, errors.codes.getFirst());
        assertTrue(errors.messages.getFirst().contains("JUL event capture failed"), errors.messages.getFirst());
    }

    @Test
    void reportsFlushFailuresSeparatelyFromWriteFailures() {
        RecordingErrorManager errors = new RecordingErrorManager();
        LogyardHandler handler = new LogyardHandler(new ThrowingOnFlush());
        handler.setErrorManager(errors);

        handler.flush();

        assertEquals(1, errors.codes.size());
        assertEquals(ErrorManager.FLUSH_FAILURE, errors.codes.getFirst());
    }

    @Test
    void neverAsksAnUninitializedRuntimeToFlush() {
        AtomicInteger resolutions = new AtomicInteger();
        LogyardHandler handler = new LogyardHandler(new UninitializedRuntime(resolutions));

        handler.flush();

        assertEquals(0, resolutions.get(), "flush must not force lazy bootstrap");
    }

    @Test
    void admitsConcurrentPublicationsExactlyOnceAndDrainsThemAllOnClose() throws Exception {
        int threads = 8;
        int perThread = 40;
        AtomicInteger delivered = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        EventSink counting = event -> delivered.incrementAndGet();
        try (DefaultLogyardRuntime runtime = runtime(counting)) {
            LogyardHandler handler = new LogyardHandler(runtime);
            List<Thread> publishers = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                publishers.add(Thread.ofPlatform().start(() -> {
                    await(start);
                    for (int emission = 0; emission < perThread; emission++) {
                        handler.publish(new LogRecord(java.util.logging.Level.INFO, "concurrent"));
                    }
                    finished.countDown();
                }));
            }
            start.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            handler.close();
            for (Thread publisher : publishers) {
                publisher.join();
            }
        }

        assertEquals(threads * perThread, delivered.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static DefaultLogyardRuntime runtime(EventSink sink) {
        return new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.TRACE, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of(),
                Duration.ofSeconds(1)));
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }

    private static final class RecordingErrorManager extends ErrorManager {
        private final List<String> messages = new ArrayList<>();
        private final List<Integer> codes = new ArrayList<>();

        @Override
        public synchronized void error(String message, Exception failure, int code) {
            messages.add(message);
            codes.add(code);
        }
    }

    private static final class ThrowingOnFlush implements AdapterRuntimeAccess {
        @Override
        public LogyardRuntime runtime() {
            throw new IllegalStateException("runtime transition in progress");
        }

        @Override
        public boolean initialized() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static final class UninitializedRuntime implements AdapterRuntimeAccess {
        private final AtomicInteger resolutions;

        private UninitializedRuntime(AtomicInteger resolutions) {
            this.resolutions = resolutions;
        }

        @Override
        public LogyardRuntime runtime() {
            resolutions.incrementAndGet();
            throw new IllegalStateException("must not be resolved");
        }

        @Override
        public boolean initialized() {
            return false;
        }

        @Override
        public void close() {
        }
    }
}
