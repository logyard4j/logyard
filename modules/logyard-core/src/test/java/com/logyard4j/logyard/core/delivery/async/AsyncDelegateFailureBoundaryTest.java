package com.logyard4j.logyard.core.delivery.async;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.BatchEventSink;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncDelegateFailureBoundaryTest {
    @ParameterizedTest
    @MethodSource("failures")
    void preservesFailurePolicyAccountingAndLockRelease(Throwable failure, boolean batch) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AsyncSinkMetrics metrics = new AsyncSinkMetrics();
        BatchEventSink delegate = new BatchEventSink() {
            @Override
            public int maximumBatchSize() {
                return 2;
            }

            @Override
            public Duration maximumBatchDelay() {
                return Duration.ZERO;
            }

            @Override
            public void acceptBatch(List<LogEvent> events) {
                accept(events.getFirst());
            }

            @Override
            public void accept(LogEvent event) {
                if (attempts.getAndIncrement() == 0) {
                    AsyncDelegateFailureBoundaryTest.<RuntimeException>throwUnchecked(failure);
                }
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        AsyncDelegateDelivery delivery = new AsyncDelegateDelivery(delegate, metrics, new AsyncSinkDiagnostics("test"));
        LogEvent event = new LogEvent(0, 0, Level.INFO, "test", null, "record", null,
                AttributeSet.EMPTY, null, 1, "test");
        Runnable deliver = () -> {
            if (batch) delivery.deliverBatch(List.of(event, event));
            else delivery.deliverEvent(event);
        };
        long eventCount = batch ? 2L : 1L;
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("failure-lock-check").factory());
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            boolean fatal = failure instanceof LinkageError;
            if (fatal) {
                assertSame(failure, assertThrows(LinkageError.class, deliver::run));
            } else {
                assertDoesNotThrow(deliver::run);
            }
            assertEquals(failure instanceof InterruptedException, Thread.currentThread().isInterrupted());
            Thread.interrupted();
            assertEquals(0L, metrics.delivered());
            assertEquals(fatal ? 0L : eventCount, metrics.emergencyFallbacks());

            // Another thread must acquire the serialization boundary after either recovery or rethrow.
            executor.submit(() -> {
                deliver.run();
                delivery.close("test");
                delivery.close("test");
            }).get(2, TimeUnit.SECONDS);
            assertEquals(2, attempts.get());
            assertEquals(eventCount, metrics.delivered());
            assertEquals(0L, metrics.dropped(Level.INFO));
            assertEquals(1, closes.get());
        } finally {
            Thread.interrupted();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static Stream<Arguments> failures() {
        return Stream.of(new IllegalStateException("expected"), new AssertionError("expected"),
                new InterruptedException("expected"), new LinkageError("expected"))
                .flatMap(failure -> Stream.of(Arguments.of(failure, false), Arguments.of(failure, true)));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable failure) throws E {
        throw (E) failure;
    }
}
