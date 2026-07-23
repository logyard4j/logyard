package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AsyncDelegateDeliveryTest {
    @Test
    void preservesBothFlushAndCloseFailures() {
        RuntimeException flushFailure = new IllegalStateException("flush failed");
        RuntimeException closeFailure = new IllegalArgumentException("close failed");
        EventSink delegate = new EventSink() {
            @Override
            public void accept(LogEvent event) {
            }

            @Override
            public void flush() {
                throw flushFailure;
            }

            @Override
            public void close() {
                throw closeFailure;
            }
        };
        AsyncDelegateDelivery delivery = new AsyncDelegateDelivery(delegate, new AsyncSinkMetrics(), new AsyncSinkDiagnostics("test"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> delivery.close("test"));

        assertSame(flushFailure, failure.getCause());
        assertEquals(1, flushFailure.getSuppressed().length);
        assertSame(closeFailure, flushFailure.getSuppressed()[0]);
    }
}
