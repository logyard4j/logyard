package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class AsyncDelegateDeliveryTest {
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

        assertEquals(1, flushAttempts.get());
        assertEquals(1, closeAttempts.get());
    }
}
