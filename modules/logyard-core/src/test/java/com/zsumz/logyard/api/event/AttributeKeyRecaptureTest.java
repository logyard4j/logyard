package com.zsumz.logyard.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AttributeKeyRecaptureTest {
    @Test
    void eventRecaptureRetainsOnlyCompleteAttributeKeys() {
        String key = "four";

        assertCompleteKeys(eventLeavingPayloadCharacters(0, key));
        assertCompleteKeys(eventLeavingPayloadCharacters(1, key));
        assertCompleteKeys(eventLeavingPayloadCharacters(key.length() - 1, key));

        LogEvent exact = eventLeavingPayloadCharacters(key.length(), key);
        assertEquals("", exact.attributes().get(key));
        assertCompleteKeys(exact);
    }

    private static LogEvent eventLeavingPayloadCharacters(int remaining, String key) {
        String payload = "p".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS - remaining);
        return new LogEvent(
                1L,
                2L,
                Level.INFO,
                "test.capture",
                null,
                "message",
                new Object[] {payload},
                AttributeSet.of(key, "value"),
                null,
                3L,
                "main");
    }

    private static void assertCompleteKeys(LogEvent event) {
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < event.attributes().size(); index++) {
            String key = event.attributes().keyAt(index);
            assertTrue(!key.isBlank());
            assertTrue(keys.add(key));
        }
        assertEquals(true, event.attributes().get("logyard.capture.truncated"));
    }
}
