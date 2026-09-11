package com.zsumz.logyard.output.json.encoding;

import com.zsumz.logyard.api.event.LogEvent;

import java.time.Instant;

/** Profile-compiled identity record used when the complete JSON exceeds its output budget. */
final class JsonFallback {
    private final String timestamp;
    private final String severity;
    private final String logger;
    private final String eventName;
    private final String body;

    JsonFallback(JsonProfile profile) {
        timestamp = profile.outputName("timestamp");
        severity = permittedName(profile, "severity_text");
        logger = permittedName(profile, "logger");
        eventName = permittedName(profile, "event_name");
        body = permittedName(profile, "body");
    }

    String encode(JsonWriter json, LogEvent event) {
        // Captured identity is at most 2 * 1,024 chars and the body at most 16,384.
        // Even sixfold JSON escaping plus five 128-char keys fits well below 262,144.
        json.reset();
        json.beginObject();
        json.field(timestamp, Instant.ofEpochMilli(event.timestampMillis()).toString());
        field(json, severity, event.level().name());
        field(json, logger, event.loggerName());
        if (event.eventName() != null && !event.eventName().isBlank()) {
            field(json, eventName, event.eventName());
        }
        if (body != null) {
            field(json, body, event.renderedMessage());
        }
        json.comma();
        json.field(JsonProfile.TRUNCATED, true);
        json.endObject();
        return json.result();
    }

    private static void field(JsonWriter json, String name, String value) {
        if (name != null) {
            json.comma();
            json.field(name, value);
        }
    }

    private static String permittedName(JsonProfile profile, String field) {
        return profile.emits(field) ? profile.outputName(field) : null;
    }
}
