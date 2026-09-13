package com.logyard4j.logyard.runtime.extension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.encoding.EventEncoder;
import com.logyard4j.logyard.api.spi.encoding.EventEncoderBoundary;
import com.logyard4j.logyard.api.spi.processing.EventProcessor;
import org.junit.jupiter.api.Test;

final class ExtensionGuardrailsTest {
    @Test
    void runtimeEncoderGuardDelegatesToTheSharedIdempotentBoundary() {
        EventEncoder guarded = EventEncoderBoundary.guard(event -> "{}");
        assertSame(guarded, ExtensionGuardrails.encoder(guarded));
    }

    @Test
    void providerEnrichersCannotSilentlyDropEvents() {
        LogEvent event = new LogEvent(
                1_000L,
                1_000_000_000L,
                Level.INFO,
                "guardrails",
                "test",
                "message",
                null,
                AttributeSet.EMPTY,
                null,
                1L,
                "main");
        EventProcessor valid = ExtensionGuardrails.enricher("valid", candidate -> candidate);
        EventProcessor dropping = ExtensionGuardrails.enricher("dropping", candidate -> null);

        assertSame(event, valid.process(event));
        assertThrows(IllegalStateException.class, () -> dropping.process(event));
    }
}
