package com.logyard4j.logyard.core.delivery.async;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.EventSink;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
    void preservesFailurePolicyAccountingAndLockRelease(Throwable failure) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AsyncSinkMetrics metrics = new AsyncSinkMetrics();
        EventSink delegate = new EventSink() {
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
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("failure-lock-check").factory());
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            boolean fatal = failure instanceof LinkageError;
            if (fatal) {
                assertSame(failure, assertThrows(LinkageError.class, () -> delivery.deliverEvent(event)));
            } else {
                assertDoesNotThrow(() -> delivery.deliverEvent(event));
            }
            assertEquals(failure instanceof InterruptedException, Thread.currentThread().isInterrupted());
            Thread.interrupted();
            assertEquals(0L, metrics.delivered());
            assertEquals(fatal ? 0L : 1L, metrics.emergencyFallbacks());

            // Another thread must acquire the serialization boundary after either recovery or rethrow.
            executor.submit(() -> {
                delivery.deliverEvent(event);
                delivery.close("test");
                delivery.close("test");
            }).get(2, TimeUnit.SECONDS);
            assertEquals(2, attempts.get());
            assertEquals(1L, metrics.delivered());
            assertEquals(0L, metrics.dropped(Level.INFO));
            assertEquals(1, closes.get());
        } finally {
            Thread.interrupted();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static Stream<Throwable> failures() {
        return Stream.of(new IllegalStateException("expected"), new AssertionError("expected"),
                new InterruptedException("expected"), new LinkageError("expected"));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable failure) throws E {
        throw (E) failure;
    }
}
