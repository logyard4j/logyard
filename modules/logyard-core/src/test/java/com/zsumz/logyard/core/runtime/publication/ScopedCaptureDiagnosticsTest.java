package com.zsumz.logyard.core.runtime.publication;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.context.LogContext;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CapturedAttributeAccess;
import com.zsumz.logyard.api.ingress.IngressMetadata;
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
