package com.logyard4j.api.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class BoundedPrintfSemanticsTest {
    @Test
    void booleanHashAndStringConversionsCaptureOnlyTheirRequiredCallerBehavior() {
        AtomicInteger hashCalls = new AtomicInteger();
        AtomicInteger renderCalls = new AtomicInteger();
        Object value = new Object() {
            @Override
            public int hashCode() {
                hashCalls.incrementAndGet();
                return 0x123;
            }

            @Override
            public String toString() {
                renderCalls.incrementAndGet();
                return "value";
            }
        };

        BoundedMessageFormat.Result result =
                BoundedMessageFormat.printf("%1$b %1$h %1$s", new Object[] {value});

        assertEquals("true 123 value", result.message());
        assertEquals(1, hashCalls.get());
        assertEquals(1, renderCalls.get());
        assertFalse(result.formatFailed());
    }

    @Test
    void booleanConversionDoesNotInvokeToString() {
        Object value = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("boolean conversion must not render its argument");
            }
        };

        BoundedMessageFormat.Result result = BoundedMessageFormat.printf("%b", new Object[] {value});

        assertEquals("true", result.message());
        assertFalse(result.formatFailed());
    }
}
