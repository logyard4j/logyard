package com.zsumz.logyard.api.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AttributeSetTest {
    @Test
    void preservesInsertionOrderWhileReplacingValues() {
        AttributeSet attributes = AttributeSet.builder()
                .put("first", 1)
                .put("second", 2)
                .put("first", 3)
                .build();

        assertEquals(2, attributes.size());
        assertEquals("first", attributes.keyAt(0));
        assertEquals(3, attributes.valueAt(0));
        assertEquals("second", attributes.keyAt(1));
    }

    @Test
    void reservesTheLastBoundedSlotForTheTruncationMarkerWithoutEvaluatingDiscardedSuppliers() {
        AttributeSet.Builder builder = AttributeSet.builder(CaptureLimits.MAX_ATTRIBUTES);
        for (int index = 0; index < CaptureLimits.MAX_ATTRIBUTES - 1; index++) {
            builder.put("key." + index, index);
        }
        AtomicInteger evaluations = new AtomicInteger();

        builder.putSupplied("discarded", evaluations::incrementAndGet);
        AttributeSet attributes = builder.build();

        assertEquals(0, evaluations.get());
        assertEquals(CaptureLimits.MAX_ATTRIBUTES, attributes.size());
        assertEquals(true, attributes.get("logyard.attributes.truncated"));
    }
}
