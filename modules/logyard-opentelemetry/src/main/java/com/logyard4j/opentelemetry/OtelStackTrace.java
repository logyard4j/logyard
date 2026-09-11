package com.logyard4j.opentelemetry;

import com.logyard4j.api.event.CaptureLimits;
import com.logyard4j.api.event.ExceptionSnapshot;

import java.util.IdentityHashMap;

/**
 * Renders an already-bounded exception snapshot into the scalar {@code exception.stacktrace} text.
 *
 * <p>Only frames the snapshot retained are rendered, so no unbounded text is reconstructed from a
 * live throwable. A character ceiling and shared-reference detection additionally bound the pathological
 * case of a wide suppressed-and-cause graph. Each render owns its own state, so the renderer is safe
 * for concurrent delivery threads.</p>
 */
final class OtelStackTrace {
    private static final int MAX_CHARACTERS = CaptureLimits.MAX_EVENT_TEXT_CHARS;

    private final StringBuilder rendered = new StringBuilder(1024);
    private final IdentityHashMap<ExceptionSnapshot, Boolean> visited = new IdentityHashMap<>();

    private OtelStackTrace() {
    }

    /**
     * Renders one bounded throwable graph.
     *
     * @param exception captured exception graph
     * @return bounded stack-trace text
     */
    static String render(ExceptionSnapshot exception) {
        OtelStackTrace renderer = new OtelStackTrace();
        renderer.append(exception, "", "", 0);
        return renderer.rendered.toString();
    }

    private void append(ExceptionSnapshot exception, String indent, String caption, int depth) {
        if (rendered.length() >= MAX_CHARACTERS) return;
        if (depth >= ExceptionSnapshot.MAX_CAUSE_DEPTH) {
            appendText(indent + caption + "[maximum exception rendering depth reached]");
            return;
        }
        if (visited.put(exception, Boolean.TRUE) != null) {
            appendText(indent + caption + "[shared exception reference]");
            return;
        }
        appendText(indent + caption + exception.summary());
        for (StackTraceElement frame : exception.frames()) {
            appendText("\n" + indent + "\tat " + frame);
        }
        if (exception.truncated()) {
            appendText("\n" + indent + "\t[exception capture truncated]");
        }
        for (ExceptionSnapshot suppressed : exception.suppressed()) {
            appendText("\n");
            append(suppressed, indent + "\t", "Suppressed: ", depth + 1);
        }
        if (exception.cause() != null) {
            appendText("\n");
            append(exception.cause(), indent, "Caused by: ", depth + 1);
        }
    }

    private void appendText(String value) {
        int remaining = MAX_CHARACTERS - rendered.length();
        if (remaining <= 0) {
            return;
        }
        if (value.length() <= remaining) {
            rendered.append(value);
            return;
        }
        int end = remaining;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        rendered.append(value, 0, end);
    }
}
