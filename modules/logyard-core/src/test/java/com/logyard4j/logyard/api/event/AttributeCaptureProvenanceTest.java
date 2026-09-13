package com.logyard4j.logyard.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.Level;

import org.junit.jupiter.api.Test;

final class AttributeCaptureProvenanceTest {
    @Test
    void prebuiltKeyTruncationSurvivesEventRecapture() {
        AttributeSet prebuilt = AttributeSet.of("request." + "x".repeat(1_000) + ".authorization", "secret");

        LogEvent event = event(prebuilt);

        assertTrue(prebuilt.captureTruncated());
        assertEquals(true, event.attributes().get("logyard.capture.truncated"));
    }

    @Test
    void prebuiltValueTruncationSurvivesEventRecapture() {
        AttributeSet prebuilt = AttributeSet.of("payload", "x".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS + 1));

        LogEvent event = event(prebuilt);

        assertTrue(prebuilt.captureTruncated());
        assertEquals(true, event.attributes().get("logyard.capture.truncated"));
    }

    @Test
    void mergeAndSystemEnrichmentPreserveCaptureProvenance() {
        AttributeSet truncated = AttributeSet.of("x".repeat(1_000), "value");
        AttributeSet merged = AttributeSet.of("ordinary", "value").mergedWith(truncated);
        AttributeSet enriched = merged.withSystemAttribute("logyard.test", true);

        assertTrue(merged.captureTruncated());
        assertTrue(enriched.captureTruncated());
        assertEquals(true, event(enriched).attributes().get("logyard.capture.truncated"));
    }

    private static LogEvent event(AttributeSet attributes) {
        return new LogEvent(
                1L,
                2L,
                Level.INFO,
                "provenance.test",
                null,
                "message",
                new Object[0],
                attributes,
                null,
                3L,
                "main");
    }
}
