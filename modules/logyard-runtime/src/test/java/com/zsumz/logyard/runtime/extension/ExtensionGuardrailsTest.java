package com.zsumz.logyard.runtime.extension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.processing.EventProcessor;
import org.junit.jupiter.api.Test;

final class ExtensionGuardrailsTest {
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
