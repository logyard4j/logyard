package com.logyard4j.logyard.api.ingress;

import com.logyard4j.logyard.api.event.CaptureLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IngressMetadataTest {
    @Test
    void paddedSourceNamesPreserveTheExactLimitAndLogicalIdentity() {
        String name = "w".repeat(CaptureLimits.MAX_NAME_CHARS);
        String padding = " \t\r\n".repeat(1_000);
        IngressMetadata metadata = IngressMetadata.source(42L, 17L, padding + name + padding);

        assertEquals(name, metadata.sourceThreadName());
        assertTrue(metadata.hasSourceTimestamp());
        assertEquals(42L, metadata.sourceTimestampMillis());
        assertTrue(metadata.hasSourceThreadId());
        assertEquals(17L, metadata.sourceThreadId());
    }

    @Test
    void oversizedPaddedNamesRetainTheSameUnicodeSafePrefix() {
        String prefix = "w".repeat(CaptureLimits.MAX_NAME_CHARS - 2);
        String oversized = " \t" + prefix + "\ud83d\ude80" + "tail".repeat(100_000) + "\r\n";
        IngressMetadata metadata = IngressMetadata.source(42L, oversized);

        assertEquals(prefix + "…", metadata.sourceThreadName());
        assertEquals(42L, metadata.sourceTimestampMillis());
        assertFalse(metadata.hasSourceThreadId());
    }

    @Test
    void missingNamesAndExistingTrimSemanticsArePreserved() {
        assertNull(IngressMetadata.source(42L, null).sourceThreadName());
        assertNull(IngressMetadata.source(42L, "").sourceThreadName());
        assertNull(IngressMetadata.source(42L, " \t\r\n").sourceThreadName());
        assertEquals("worker", IngressMetadata.source(42L, " \tworker\r\n").sourceThreadName());
        assertEquals("\u2003worker\u2003", IngressMetadata.source(42L, "\u2003worker\u2003").sourceThreadName());
    }
}
