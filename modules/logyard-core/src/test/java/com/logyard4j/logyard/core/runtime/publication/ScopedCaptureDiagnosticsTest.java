package com.logyard4j.logyard.core.runtime.publication;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.context.LogContext;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.CapturedAttributeAccess;
import com.logyard4j.logyard.api.ingress.IngressMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScopedCaptureDiagnosticsTest {
    @Test
    void anEmptyScopeCanStillCarryCaptureLossThroughNestedScopesAndLowLevelIngress() {
        AttributeSet shortened = AttributeSet.builder().markCaptureTruncated().build();
        assertTrue(shortened.isEmpty());
        assertTrue(CapturedAttributeAccess.truncated(shortened));
        try (var outer = LogContext.push(shortened);
                var inner = LogContext.push(AttributeSet.EMPTY)) {
            assertTrue(CapturedAttributeAccess.truncated(LogContext.current()));
            EventDraft draft = new EventDraft("test", Level.INFO, null, "record", null,
                    AttributeSet.EMPTY, null, IngressMetadata.current());
            assertEquals(Boolean.TRUE, draft.capture().attributes().get("logyard.capture.truncated"));
        }
    }
}
