package com.zsumz.logyard.output.json.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class JsonFallbackTest {
    private static final String CONTROLS = "\u0000".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);
    private static final ResourceAttributes LARGE_RESOURCE = new ResourceAttributes(Map.of("noisy", CONTROLS));

    @Test
    void preservesPresetFieldNamesWhenExceptionAndEscapingForceFallback() {
        for (String preset : List.of("logyard", "ecs", "compact")) {
            JsonProfile profile = JsonProfile.named(preset);
            String json = new JsonEncoder(LARGE_RESOURCE, profile).encode(oversizedEvent());

            assertFallback(json);
            assertTrue(json.contains('"' + profile.outputName("timestamp") + "\":\"1970-01-01T00:00:00Z\""));
            assertTrue(json.contains('"' + profile.outputName("body") + "\":\"body-secret\""));
            assertTrue(json.contains('"' + profile.outputName("logger") + "\":\"logger-secret\""));
            assertTrue(json.contains('"' + profile.outputName("event_name") + "\":\"event.identity\""));
            assertFalse(json.contains('"' + profile.outputName("resource") + "\":"));
            if (!"logyard".equals(preset)) {
                assertFalse(json.contains("\"timestamp\":"));
            }
        }
    }

    @Test
    void exclusionsAndRenamedTimestampHoldForNormalAndTruncatedRecords() {
        for (String preset : List.of("logyard", "ecs", "compact")) {
            JsonProfile profile = JsonProfile.custom("private", preset,
                    Map.of("timestamp", "recorded_at", "event_name", "identity"),
                    List.of("body", "message_template", "logger"), JsonAttributeTransform.nested());
            for (boolean oversized : new boolean[] {false, true}) {
                ResourceAttributes resource = oversized ? LARGE_RESOURCE : ResourceAttributes.service("orders", "test", "1");
                String json = new JsonEncoder(resource, profile).encode(oversized ? oversizedEvent() : smallEvent());

                JsonSyntaxValidator.requireValid(json);
                assertTrue(json.contains("\"recorded_at\":\"1970-01-01T00:00:00Z\""));
                assertTrue(json.contains("\"identity\":\"event.identity\""));
                assertFalse(json.contains("body-secret"));
                assertFalse(json.contains("logger-secret"));
                assertFalse(json.contains("\"timestamp\":"));
                assertFalse(json.contains("\"@timestamp\":"));
                if (oversized) {
                    assertFallback(json);
                    assertFalse(json.contains('"' + profile.outputName("resource") + "\":"));
                }
            }
        }
    }

    @Test
    void eventNameOnlyFallbackDoesNotRestoreOtherIdentityFields() {
        JsonProfile profile = JsonProfile.custom("identity", "ecs", Map.of("timestamp", "time"),
                List.of("body", "message_template", "logger", "severity_text", "severity_number",
                        "observed_timestamp_unix_nano", "thread"), JsonAttributeTransform.nested());

        assertEquals("{\"time\":\"1970-01-01T00:00:00Z\",\"ecs.version\":\"9.4.0\",\"event.action\":\"event.identity\","
                        + "\"logyard.output.truncated\":true}",
                new JsonEncoder(LARGE_RESOURCE, profile).encode(oversizedEvent()));
    }

    @Test
    void largestEscapedIdentityAndFieldNamesFitTheFallbackBudget() {
        String escapedName = "\"".repeat(127);
        JsonProfile profile = JsonProfile.custom("escaped", "logyard", Map.of(
                "timestamp", "t" + escapedName, "logger", "l" + escapedName,
                "event_name", "e" + escapedName, "body", "b" + escapedName,
                "severity_text", "s" + escapedName), List.of(), JsonAttributeTransform.nested());
        LogEvent event = new LogEvent(0, 1, Level.ERROR, "\u0000".repeat(CaptureLimits.MAX_NAME_CHARS),
                "\u0000".repeat(CaptureLimits.MAX_NAME_CHARS), "{}", new Object[] {CONTROLS},
                AttributeSet.EMPTY, failure(), 1, "main");

        String json = new JsonEncoder(LARGE_RESOURCE, profile).encode(event);

        assertFallback(json);
        assertFalse(json.contains("\"resource\":"));
        assertTrue(json.contains("\\u0000"));
        assertTrue(json.contains("b\\\""));
    }

    @Test
    void missingEventNameDoesNotCauseAnExcludedBodyToReappear() {
        JsonProfile profile = JsonProfile.custom("private", "compact", Map.of(),
                List.of("body", "message_template", "logger"), JsonAttributeTransform.nested());
        LogEvent event = new LogEvent(0, 1, Level.ERROR, "logger-secret", null, "body-secret", null,
                AttributeSet.of("payload", CONTROLS), failure(), 1, "main");

        String json = new JsonEncoder(LARGE_RESOURCE, profile).encode(event);

        assertFallback(json);
        assertFalse(json.contains("body-secret"));
        assertFalse(json.contains("logger-secret"));
        assertFalse(json.contains("\"event\":"));
    }

    @Test
    void rejectsUserFieldsAndFlatteningPrefixesThatCanOverwriteTheDiagnostic() {
        for (String field : JsonProfile.FIELDS) {
            assertThrows(IllegalArgumentException.class, () -> JsonProfile.custom("reserved", "logyard",
                    Map.of(field, "logyard.output.truncated"), List.of(), JsonAttributeTransform.nested()));
        }
        assertThrows(IllegalArgumentException.class, () -> JsonProfile.custom("reserved", "logyard",
                Map.of(), List.of(), new JsonAttributeTransform(JsonAttributeTransform.Mode.FLATTEN,
                        "logyard.", List.of(), List.of(), Map.of())));
    }

    private static void assertFallback(String json) {
        JsonSyntaxValidator.requireValid(json);
        assertTrue(json.length() <= JsonOutputLimits.MAX_RECORD_CHARACTERS);
        assertTrue(json.contains("\"logyard.output.truncated\":true"));
    }

    private static LogEvent oversizedEvent() {
        return new LogEvent(0, 1, Level.ERROR, "logger-secret", "event.identity", "body-secret", null,
                AttributeSet.of("payload", CONTROLS), failure(), 1, "main");
    }

    private static LogEvent smallEvent() {
        return new LogEvent(0, 1, Level.ERROR, "logger-secret", "event.identity", "body-secret", null,
                AttributeSet.EMPTY, null, 1, "main");
    }

    private static Throwable failure() {
        return new IllegalStateException("\u0000".repeat(CaptureLimits.MAX_EVENT_EXCEPTION_MESSAGE_CHARS));
    }
}
