package com.logyard4j.logyard.api.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AttributeSnapshotOwnershipTest {
    enum Capture {
        ASSEMBLY, RECAPTURE;

        AttributeSet capture(AttributeSet.Builder builder, CaptureContext context) {
            if (this == ASSEMBLY) {
                return CaptureContext.within(context, builder::build);
            }
            return builder.build().recapture(context);
        }
    }

    @Test
    void reusingBuilderAndMutatingCallerValuesCannotChangePublishedSnapshots() {
        String longKey = "x".repeat(CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS + 1);
        List<String> callerValue = new ArrayList<>(List.of("before"));
        AttributeSet.Builder builder = AttributeSet.builder(2).put(longKey, callerValue).put("status", "old");
        AttributeSet first = builder.build();
        String capturedKey = first.keyAt(0);

        callerValue.set(0, "after");
        builder.put("status", "new").put(capturedKey, "literal collision");
        AttributeSet second = builder.build();

        assertEquals(2, first.size());
        assertEquals(List.of("before"), first.get(capturedKey));
        assertEquals("old", first.get("status"));
        assertEquals("literal collision", second.get(capturedKey));
        assertTrue(second.toMap().containsValue(List.of("after")));
        assertEquals("new", second.get("status"));
    }

    @ParameterizedTest
    @EnumSource(Capture.class)
    void exhaustedEntryBudgetOmitsUncapturedSlotsAndKeepsKeyIdentity(Capture capture) {
        String longKey = "x".repeat(CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS + 1);
        AttributeSet.Builder builder = AttributeSet.builder(2).put(longKey, 1).put("tail", 2);
        for (int retained = 0; retained <= 2; retained++) {
            CaptureContext context = CaptureContext.forAttributes(new CaptureAllowance(10, retained, 1_000));
            AttributeSet result = capture.capture(builder, context);
            assertEquals(retained, result.size());
            assertEquals(retained, result.toMap().size());
            assertTrue(result.captureTruncated());
            if (retained > 0) {
                assertEquals(1, result.valueAt(0));
                assertTrue(CapturedAttributeAccess.normalizedKey(result, 0));
            }
            if (retained == 2) {
                assertEquals(2, result.get("tail"));
            }
        }
    }
}
