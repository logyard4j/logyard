package com.logyard4j.output.json.encoding;

import com.logyard4j.api.event.LogEvent;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/** Owns one record buffer until its destination finishes consuming the complete record. */
final class JsonUtf8Output implements Consumer<LogEvent> {
    private final JsonUtf8Buffer buffer = new JsonUtf8Buffer();
    private final JsonEventWriter writer;
    private final ObjIntConsumer<byte[]> records;
    private Phase phase = Phase.READY;

    JsonUtf8Output(ResourceAttributes resource, JsonProfile profile, ObjIntConsumer<byte[]> records) {
        this.records = Objects.requireNonNull(records, "records");
        writer = new JsonEventWriter(resource, profile, buffer);
    }

    @Override
    public synchronized void accept(LogEvent event) {
        if (phase != Phase.READY) {
            throw new IllegalStateException("reentrant UTF-8 output would overwrite an active record");
        }
        phase = Phase.WRITING;
        try {
            writer.write(event);
            records.accept(buffer.bytes(), buffer.length());
        } finally {
            try {
                buffer.reset();
            } finally {
                phase = Phase.READY;
            }
        }
    }

    int retainedCapacity() {
        return buffer.capacity();
    }

    private enum Phase {
        READY,
        WRITING
    }
}
