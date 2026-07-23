package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.BatchEventSink;
import com.zsumz.logyard.api.spi.output.EventSink;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncBatchPolicyTest {
    @Test
    void representsNonBatchDelegatesAsSingletonDelivery() {
        EventSink delegate = ignored -> { };

        AsyncBatchPolicy policy = AsyncBatchPolicy.from(delivery(delegate));

        assertFalse(policy.enabled());
        assertEquals(1, policy.maximumSize());
        assertEquals(0L, policy.maximumDelayNanos());
    }

    @Test
    void validatesTheBatchContractBeforeStartingAWorker() {
        assertThrows(IllegalArgumentException.class, () -> AsyncBatchPolicy.from(delivery(new ConfigurableBatchSink(0, Duration.ZERO))));
        assertThrows(IllegalArgumentException.class, () -> AsyncBatchPolicy.from(delivery(new ConfigurableBatchSink(1, Duration.ofMinutes(2)))));
        assertTrue(AsyncBatchPolicy.from(delivery(new ConfigurableBatchSink(64, Duration.ofMillis(10)))).enabled());
    }

    private static AsyncDelegateDelivery delivery(EventSink delegate) {
        return new AsyncDelegateDelivery(delegate, new AsyncSinkMetrics(), new AsyncSinkDiagnostics("test"));
    }

    private record ConfigurableBatchSink(int maximumBatchSize, Duration maximumBatchDelay) implements BatchEventSink {
        @Override
        public void acceptBatch(List<LogEvent> events) {
        }
    }
}
