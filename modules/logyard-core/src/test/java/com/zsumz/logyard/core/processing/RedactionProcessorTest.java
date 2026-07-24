package com.zsumz.logyard.core.processing;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RedactionProcessorTest {
    @Test
    void redactsSensitiveLeafNamesAfterLongKeyNormalization() {
        String authorization = "namespace.".repeat(40) + "authorization";
        String sessionToken = "namespace.".repeat(40) + "session.token";
        AttributeSet attributes = AttributeSet.builder()
                .put(authorization, "bearer-secret")
                .put(sessionToken, "session-secret")
                .build();

        LogEvent redacted = new RedactionProcessor(List.of("authorization", "*.token")).process(event(attributes));

        assertEquals("[REDACTED]", redacted.attributes().valueAt(0));
        assertEquals("[REDACTED]", redacted.attributes().valueAt(1));
        assertTrue(redacted.attributes().keyAt(0).endsWith(".authorization"));
        assertTrue(redacted.attributes().keyAt(1).endsWith(".token"));
    }

    @Test
    void distinctLongPathsWithTheSamePrefixAndLeafRemainDistinct() {
        String shared = "namespace.".repeat(40);
        AttributeSet attributes = AttributeSet.builder()
                .put(shared + "first-middle.authorization", "first")
                .put(shared + "second-middle.authorization", "second")
                .build();

        assertEquals(2, attributes.size());
        assertNotEquals(attributes.keyAt(0), attributes.keyAt(1));
        assertTrue(attributes.keyAt(0).length() <= CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
        assertTrue(attributes.keyAt(1).length() <= CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
    }

    private static LogEvent event(AttributeSet attributes) {
        return new LogEvent(
                1L,
                1_000_000L,
                Level.INFO,
                "test.Logger",
                null,
                "message",
                null,
                attributes,
                null,
                7L,
                "test");
    }
}
