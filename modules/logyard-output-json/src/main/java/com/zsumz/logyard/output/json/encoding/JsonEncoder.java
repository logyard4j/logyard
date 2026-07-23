package com.zsumz.logyard.output.json.encoding;

import com.zsumz.logyard.api.event.ExceptionSnapshot;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Direct, non-thread-safe JSON encoder for Logyard's event model. No event tree is created. */
public final class JsonEncoder implements EventEncoder {
    private final ResourceAttributes resource;
    private final JsonProfile profile;
    private final JsonWriter json = new JsonWriter(1024);

    public JsonEncoder(ResourceAttributes resource) {
        this(resource, JsonProfile.named("logyard"));
    }

    public JsonEncoder(ResourceAttributes resource, JsonProfile profile) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public String encode(LogEvent event) {
        Objects.requireNonNull(event, "event");
        json.reset();
        json.beginObject();
        boolean first = true;
        first = stringField(first, "timestamp", Instant.ofEpochMilli(event.timestampMillis()).toString());
        first = longField(first, "observed_timestamp_unix_nano", event.observedTimestampUnixNanos());
        first = longField(first, "severity_number", event.level().severityNumber());
        first = stringField(first, "severity_text", event.level().name());
        first = stringField(first, "logger", event.loggerName());
        if (event.eventName() != null && !event.eventName().isBlank()) {
            first = stringField(first, "event_name", event.eventName());
        }
        first = stringField(first, "body", event.renderedMessage());
        if (event.messageTemplate() != null) {
            first = stringField(first, "message_template", event.messageTemplate());
        }
        first = attributes(first, event);
        if (profile.emits("resource")) {
            first = beginField(first, profile.outputName("resource"));
            json.value(resource.values());
        }
        if (profile.emits("thread")) {
            first = beginField(first, profile.outputName("thread"));
            json.beginObject();
            json.field("id", event.threadId());
            json.comma();
            json.field("name", event.threadName());
            json.endObject();
        }
        if (event.exception() != null && profile.emits("exception")) {
            first = beginField(first, profile.outputName("exception"));
            exception(event.exception());
        }
        json.endObject();
        return json.result();
    }

    @Override
    public String mediaType() {
        return "application/json; charset=utf-8";
    }

    public JsonProfile profile() {
        return profile;
    }

    private boolean attributes(boolean first, LogEvent event) {
        JsonAttributeTransform transform = profile.attributes();
        if (transform.mode() == JsonAttributeTransform.Mode.DROP || !profile.emits("attributes")) {
            return first;
        }
        if (transform.mode() == JsonAttributeTransform.Mode.NESTED) {
            first = beginField(first, profile.outputName("attributes"));
            json.beginObject();
            boolean firstAttribute = true;
            Set<String> names = collisionNames(transform);
            for (int index = 0; index < event.attributes().size(); index++) {
                String source = event.attributes().keyAt(index);
                if (!transform.includes(source)) {
                    continue;
                }
                String output = transform.outputName(source);
                requireUniqueAttribute(names, output);
                if (!firstAttribute) {
                    json.comma();
                }
                firstAttribute = false;
                json.name(output);
                json.value(event.attributes().valueAt(index));
            }
            json.endObject();
            return first;
        }
        Set<String> names = collisionNames(transform);
        for (int index = 0; index < event.attributes().size(); index++) {
            String source = event.attributes().keyAt(index);
            if (!transform.includes(source)) {
                continue;
            }
            String output = transform.prefix() + transform.outputName(source);
            requireUniqueAttribute(names, output);
            first = beginField(first, output);
            json.value(event.attributes().valueAt(index));
        }
        return first;
    }

    private static Set<String> collisionNames(JsonAttributeTransform transform) {
        return transform.requiresCollisionCheck() ? new HashSet<>() : null;
    }

    private static void requireUniqueAttribute(Set<String> names, String output) {
        if (names != null && !names.add(output)) {
            throw new IllegalArgumentException(
                    "JSON attribute transforms produce duplicate field '" + output + "'");
        }
    }

    private boolean stringField(boolean first, String canonical, String value) {
        if (!profile.emits(canonical)) {
            return first;
        }
        first = beginField(first, profile.outputName(canonical));
        json.string(value);
        return first;
    }

    private boolean longField(boolean first, String canonical, long value) {
        if (!profile.emits(canonical)) {
            return first;
        }
        first = beginField(first, profile.outputName(canonical));
        json.number(value);
        return first;
    }

    private boolean beginField(boolean first, String outputName) {
        if (!first) {
            json.comma();
        }
        json.name(outputName);
        return false;
    }

    private void exception(ExceptionSnapshot exception) {
        json.beginObject();
        json.field("type", exception.type());
        if (exception.message() != null) {
            json.comma();
            json.field("message", exception.message());
        }
        json.comma();
        json.name("stacktrace");
        json.beginArray();
        for (int index = 0; index < exception.frames().size(); index++) {
            if (index > 0) {
                json.comma();
            }
            json.string(exception.frames().get(index).toString());
        }
        json.endArray();
        if (exception.truncated()) {
            json.comma();
            json.field("truncated", true);
        }
        if (!exception.suppressed().isEmpty()) {
            json.comma();
            json.name("suppressed");
            json.beginArray();
            for (int index = 0; index < exception.suppressed().size(); index++) {
                if (index > 0) {
                    json.comma();
                }
                exception(exception.suppressed().get(index));
            }
            json.endArray();
        }
        if (exception.cause() != null) {
            json.comma();
            json.name("cause");
            exception(exception.cause());
        }
        json.endObject();
    }
}
