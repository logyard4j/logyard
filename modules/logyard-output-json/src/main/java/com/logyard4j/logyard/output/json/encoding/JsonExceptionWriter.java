package com.logyard4j.logyard.output.json.encoding;

import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.event.ExceptionSnapshot;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Renders the bounded exception subgraph for one encoder invocation. */
final class JsonExceptionWriter {
    private final JsonWriter json;
    private final IdentityHashMap<ExceptionSnapshot, Boolean> rendered = new IdentityHashMap<>();
    private int remainingNodes;

    JsonExceptionWriter(JsonWriter json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    void reset() {
        rendered.clear();
        remainingNodes = CaptureLimits.MAX_EVENT_EXCEPTION_NODES;
    }

    void write(ExceptionSnapshot exception) {
        write(exception, 0);
    }

    private void write(ExceptionSnapshot exception, int depth) {
        if (depth >= ExceptionSnapshot.MAX_CAUSE_DEPTH) {
            truncated("[maximum exception rendering depth reached]");
            return;
        }
        if (remainingNodes == 0 || rendered.put(exception, Boolean.TRUE) != null) {
            truncated("[shared or bounded exception reference]");
            return;
        }
        remainingNodes--;
        json.beginObject();
        json.field("type", exception.type());
        if (exception.message() != null) {
            json.comma();
            json.field("message", exception.message());
        }
        stacktrace(exception);
        if (exception.truncated()) {
            json.comma();
            json.field("truncated", true);
        }
        suppressed(exception, depth);
        if (exception.cause() != null) {
            json.comma();
            json.name("cause");
            write(exception.cause(), depth + 1);
        }
        json.endObject();
    }

    private void stacktrace(ExceptionSnapshot exception) {
        json.comma();
        json.name("stacktrace");
        json.beginArray();
        for (int index = 0; index < exception.frames().size(); index++) {
            if (!json.claimEntry()) {
                comma(index);
                json.string("[output traversal budget exhausted]");
                break;
            }
            comma(index);
            json.string(exception.frames().get(index).toString());
        }
        json.endArray();
    }

    private void suppressed(ExceptionSnapshot exception, int depth) {
        if (exception.suppressed().isEmpty()) {
            return;
        }
        json.comma();
        json.name("suppressed");
        json.beginArray();
        for (int index = 0; index < exception.suppressed().size(); index++) {
            if (!json.claimEntry()) {
                comma(index);
                json.string("[output traversal budget exhausted]");
                break;
            }
            comma(index);
            write(exception.suppressed().get(index), depth + 1);
        }
        json.endArray();
    }

    private void truncated(String message) {
        json.markTraversalTruncated();
        json.string(message);
    }

    private void comma(int index) {
        if (index > 0) {
            json.comma();
        }
    }
}
