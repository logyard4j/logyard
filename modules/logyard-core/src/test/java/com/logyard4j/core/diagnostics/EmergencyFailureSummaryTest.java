package com.logyard4j.core.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class EmergencyFailureSummaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"x", "界"})
    void largeCallerMessagesDoNotRequireLargeTemporarySummaries(String character) {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof com.sun.management.ThreadMXBean);
        var allocationBean = (com.sun.management.ThreadMXBean) platformBean;
        assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
        allocationBean.setThreadAllocatedMemoryEnabled(true);
        var failure = new IllegalStateException(character.repeat(2 * 1024 * 1024));
        EmergencyText.failureSummary(failure, 512);
        long threadId = Thread.currentThread().threadId();

        long before = allocationBean.getThreadAllocatedBytes(threadId);
        assumeTrue(before >= 0L, "allocation counter is unavailable for this thread");
        String summary = EmergencyText.failureSummary(failure, 512);
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;

        assertTrue(allocated >= 0L, "allocation counter became unavailable");
        assertEquals(512, summary.length());
        assertTrue(summary.endsWith("…"));
        assertTrue(allocated < 65_536L, () -> "bounded failure summary allocated " + allocated + " bytes");
    }

    @Test
    void preservesEscapingAndTruncationAtEverySmallLimit() {
        String[] messages = {null, "", " \t\n", "ordinary", "𐐀\u2028\n\u001b".repeat(128), " ".repeat(128) + "tail"};
        for (String message : messages) {
            var failure = new IllegalStateException(message);
            String type = failure.getClass().getName();
            String complete = message == null || message.isBlank() ? type : type + ": " + message;
            for (int maximum = 1; maximum <= 128; maximum++) {
                assertEquals(EmergencyText.sanitize(complete, maximum), EmergencyText.failureSummary(failure, maximum),
                        "maximum=" + maximum);
            }
        }
    }

    @Test
    void preservesTheBoundedFallbackForRecoverableMessageAccessorFailures() {
        var failure = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new AssertionError("accessor failed");
            }
        };
        String complete = failure.getClass().getName() + ": [message accessor failed: java.lang.AssertionError]";
        for (int maximum = 1; maximum <= 128; maximum++) {
            assertEquals(EmergencyText.sanitize(complete, maximum), EmergencyText.failureSummary(failure, maximum));
        }
    }
}
