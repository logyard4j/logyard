package com.zsumz.logyard.output.json.encoding;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;

import java.time.Instant;
import java.util.Objects;

/** Thread-safe direct JSON encoder for Logyard's event model. No event tree is created. */
public final class JsonEncoder implements EventEncoder {
    private final ResourceAttributes resource;
    private final JsonProfile profile;
    private final JsonFallback fallback;
    private final EcsProjection ecs;
    private final JsonAttributesWriter attributes;
    private final JsonWriter json = new JsonWriter(1024);
    private final JsonExceptionWriter exceptions = new JsonExceptionWriter(json);

    public JsonEncoder(ResourceAttributes resource) {
        this(resource, JsonProfile.named("logyard"));
    }

    public JsonEncoder(ResourceAttributes resource, JsonProfile profile) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.profile = Objects.requireNonNull(profile, "profile");
        fallback = new JsonFallback(profile);
        ecs = profile.ecs() ? new EcsProjection(resource) : null;
        attributes = new JsonAttributesWriter(json, profile);
    }

    @Override
    public synchronized String encode(LogEvent event) {
        Objects.requireNonNull(event, "event");
        exceptions.reset();
        json.reset();
        try {
            return encodeEvent(event);
        } catch (JsonLimitExceeded limit) {
            return fallback.encode(json, event);
        }
    }

    private String encodeEvent(LogEvent event) {
        json.beginObject();
        boolean first = true;
        first = stringField(first, "timestamp", Instant.ofEpochMilli(event.timestampMillis()).toString());
        first = ecs == null
                ? longField(first, "observed_timestamp_unix_nano", event.observedTimestampUnixNanos())
                : stringField(first, "observed_timestamp_unix_nano", Instant.ofEpochSecond(0, event.observedTimestampUnixNanos()).toString());
        if (ecs != null) {
            first = beginField(first, "ecs.version");
            json.string(EcsProjection.VERSION);
        }
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
        first = attributes.write(first, event.attributes());
        if (profile.emits("resource")) {
            first = beginField(first, profile.outputName("resource"));
            if (ecs == null) {
                json.value(resource.values());
            } else {
                ecs.resource(json);
            }
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
            if (ecs == null) {
                exceptions.write(event.exception());
            } else {
                EcsExceptionWriter.write(json, event.exception());
            }
        }
        if (json.traversalTruncated() || event.renderedMessageTruncated()) {
            first = beginField(first, JsonProfile.TRUNCATED);
            json.value(true);
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

}
