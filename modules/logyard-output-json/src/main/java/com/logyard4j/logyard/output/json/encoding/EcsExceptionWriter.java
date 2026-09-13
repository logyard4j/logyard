package com.logyard4j.logyard.output.json.encoding;

import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.event.ExceptionSnapshot;

import java.util.IdentityHashMap;

/** Bounded plain-text stack trace for ECS error.stack_trace, including causes and suppression. */
final class EcsExceptionWriter {
    private final StringBuilder text = new StringBuilder(256);
    private final IdentityHashMap<ExceptionSnapshot, Boolean> seen = new IdentityHashMap<>();
    private final JsonWriter json;

    private EcsExceptionWriter(JsonWriter json) {
        this.json = json;
    }

    static void write(JsonWriter json, ExceptionSnapshot exception) {
        json.beginObject();
        json.field("type", exception.type());
        if (exception.message() != null) {
            json.comma();
            json.field("message", exception.message());
        }
        EcsExceptionWriter trace = new EcsExceptionWriter(json);
        trace.append(exception, "", 0);
        json.comma();
        json.field("stack_trace", trace.text.toString());
        json.endObject();
    }

    private void append(ExceptionSnapshot exception, String prefix, int depth) {
        if (depth >= ExceptionSnapshot.MAX_CAUSE_DEPTH || seen.size() >= CaptureLimits.MAX_EVENT_EXCEPTION_NODES
                || seen.put(exception, Boolean.TRUE) != null) {
            json.markTraversalTruncated();
            append(prefix + "[shared or bounded exception reference]\n");
            return;
        }
        append(prefix + exception.summary() + '\n');
        for (StackTraceElement frame : exception.frames()) {
            if (!json.claimEntry() || full()) {
                break;
            }
            append("\tat " + frame + '\n');
        }
        if (exception.truncated()) {
            json.markTraversalTruncated();
            append("\t... exception snapshot bounded\n");
        }
        for (ExceptionSnapshot suppressed : exception.suppressed()) {
            if (!json.claimEntry() || full()) {
                break;
            }
            append(suppressed, "Suppressed: ", depth + 1);
        }
        if (exception.cause() != null && !full()) {
            append(exception.cause(), "Caused by: ", depth + 1);
        }
    }

    private void append(String value) {
        int remaining = CaptureLimits.MAX_TEXT_CHARS - text.length();
        if (value.length() <= remaining) {
            text.append(value);
            return;
        }
        json.markTraversalTruncated();
        if (remaining > 0) {
            int end = remaining - 1;
            if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) {
                end--;
            }
            text.append(value, 0, end).append('…');
        }
    }

    private boolean full() {
        if (text.length() >= CaptureLimits.MAX_TEXT_CHARS - 1) {
            json.markTraversalTruncated();
            return true;
        }
        return false;
    }
}
