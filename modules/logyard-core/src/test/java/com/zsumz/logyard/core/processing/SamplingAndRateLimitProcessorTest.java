package com.zsumz.logyard.core.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class SamplingAndRateLimitProcessorTest {
    @Test
    void deterministicSamplingNeverDropsWarnOrError() {
        LogEvent info = event(Level.INFO, "orders", "trace-7");
        SamplingProcessor half = new SamplingProcessor(0.5d, "trace", 17L);

        LogEvent first = half.process(info);
        for (int index = 0; index < 32; index++) {
            assertSame(first, half.process(info));
        }
        assertNull(new SamplingProcessor(0.0d, "logger", 0L).process(info));
        LogEvent warning = event(Level.WARN, "orders", "trace-7");
        assertSame(warning, new SamplingProcessor(0.0d, "event-instance", 0L).process(warning));
    }

    @Test
    void samplingRejectsUnboundedOrUnknownKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> new SamplingProcessor(0.5d, "unknown", 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new SamplingProcessor(0.5d, "attribute:bad\nkey", 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new SamplingProcessor(0.5d, "attribute:" + "x".repeat(257), 0L));
    }

    @Test
    void keyedTokenBucketsRefillAndRemainBounded() {
        AtomicLong now = new AtomicLong(10_000L);
        RateLimitProcessor limiter = new RateLimitProcessor(
                2.0d, 2, "logger", 2, now::get);
        LogEvent orders = event(Level.INFO, "orders", "trace-1");

        assertSame(orders, limiter.process(orders));
        assertSame(orders, limiter.process(orders));
        assertNull(limiter.process(orders));
        now.addAndGet(500_000_000L);
        assertSame(orders, limiter.process(orders));
        assertNull(limiter.process(orders));

        LogEvent billing = event(Level.INFO, "billing", "trace-2");
        LogEvent shipping = event(Level.INFO, "shipping", "trace-3");
        LogEvent returns = event(Level.INFO, "returns", "trace-4");
        assertSame(billing, limiter.process(billing));
        assertNull(limiter.process(shipping));
        assertSame(returns, limiter.process(returns));
        assertNull(limiter.process(event(Level.INFO, "fraud", "trace-5")));
        assertEquals(2, limiter.trackedKeys());
    }

    @Test
    void rateLimitingNeverDropsWarnAndRejectsUnsafeKeys() {
        RateLimitProcessor limiter = new RateLimitProcessor(
                0.001d, 1, "global", 4, () -> 1L);
        LogEvent info = event(Level.INFO, "orders", "trace-1");
        assertSame(info, limiter.process(info));
        assertNull(limiter.process(info));
        LogEvent error = event(Level.ERROR, "orders", "trace-1");
        assertSame(error, limiter.process(error));
        assertEquals(1, limiter.trackedKeys());

        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitProcessor(1.0d, 1, "unknown", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitProcessor(1.0d, 1, "attribute:bad\tkey", 1));
    }

    private static LogEvent event(Level level, String logger, String traceId) {
        return new LogEvent(
                1_000L,
                1_000_000_000L,
                level,
                logger,
                "order.accepted",
                "accepted {}",
                new Object[] {7},
                AttributeSet.builder().put("trace_id", traceId).build(),
                null,
                7L,
                "main");
    }
}
