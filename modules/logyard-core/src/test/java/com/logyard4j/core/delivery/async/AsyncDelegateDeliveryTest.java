package com.logyard4j.core.delivery.async;

import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.spi.output.EventSink;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class AsyncDelegateDeliveryTest {
    @Test
    void closeRejectsReentrantDeliveryBeforeInvokingEitherLifecycleCallback() {
        AtomicInteger accepts = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AsyncDelegateDelivery[] owner = new AsyncDelegateDelivery[1];
        LogEvent event = new LogEvent(0L, 0L, Level.INFO, "test", "test", "record", null,
                AttributeSet.EMPTY, null, 1L, "test");
        EventSink delegate = new EventSink() {
            @Override
            public void accept(LogEvent value) {
                accepts.incrementAndGet();
            }

            @Override
            public void flush() {
                flushes.incrementAndGet();
                reenter();
            }

            @Override
            public void close() {
                closes.incrementAndGet();
                reenter();
            }

            private void reenter() {
                owner[0].deliverEvent(event);
                owner[0].deliverInternalEvent(event);
                owner[0].flush();
                owner[0].close("test");
            }
        };
        AsyncSinkMetrics metrics = new AsyncSinkMetrics();
        owner[0] = new AsyncDelegateDelivery(delegate, metrics, new AsyncSinkDiagnostics("test"));
        owner[0].close("test");

        assertEquals(0, accepts.get());
        assertEquals(1, flushes.get());
        assertEquals(1, closes.get());
        assertEquals(0L, metrics.delivered());
        assertEquals(2L, metrics.dropped(Level.INFO));
    }

    @Test
    void isolatesRecoverableFlushAndCloseErrorsAndAttemptsBoth() {
        AtomicInteger flushAttempts = new AtomicInteger();
        AtomicInteger closeAttempts = new AtomicInteger();
        EventSink delegate = new EventSink() {
            @Override
            public void accept(LogEvent event) {
            }

            @Override
            public void flush() {
                flushAttempts.incrementAndGet();
                throw new AssertionError("flush failed");
            }

            @Override
            public void close() {
                closeAttempts.incrementAndGet();
                throw new AssertionError("close failed");
            }
        };
        AsyncDelegateDelivery delivery = new AsyncDelegateDelivery(delegate, new AsyncSinkMetrics(), new AsyncSinkDiagnostics("test"));

        assertDoesNotThrow(() -> delivery.close("test"));
        assertDoesNotThrow(() -> delivery.close("test"));

        assertEquals(1, flushAttempts.get());
        assertEquals(1, closeAttempts.get());
    }
}
