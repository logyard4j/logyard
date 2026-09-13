package com.logyard4j.output.json.encoding;

import com.logyard4j.api.annotation.InternalApi;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.encoding.EventEncoder;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/** Thread-safe direct JSON encoder for Logyard's event model. No event tree is created. */
public final class JsonEncoder implements EventEncoder {
    private final ResourceAttributes resource;
    private final JsonProfile profile;
    private final JsonBuffer buffer = new JsonBuffer(1024, JsonOutputLimits.MAX_RECORD_CHARACTERS);
    private final JsonEventWriter writer;

    public JsonEncoder(ResourceAttributes resource) {
        this(resource, JsonProfile.named("logyard"));
    }

    public JsonEncoder(ResourceAttributes resource, JsonProfile profile) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.profile = Objects.requireNonNull(profile, "profile");
        writer = new JsonEventWriter(resource, profile, buffer);
    }

    @Override
    public synchronized String encode(LogEvent event) {
        writer.write(event);
        return buffer.result();
    }

    /**
     * Creates an independent bounded UTF-8 writer for one output.
     *
     * @param records consumes the specified prefix before returning, without retaining or modifying the array
     * @return serialized event writer with output-owned reusable storage
     * @hidden
     */
    @InternalApi
    public Consumer<LogEvent> utf8Records(ObjIntConsumer<byte[]> records) {
        return new JsonUtf8Output(resource, profile, records);
    }

    @Override
    public String mediaType() {
        return "application/json; charset=utf-8";
    }

    public JsonProfile profile() {
        return profile;
    }
}
