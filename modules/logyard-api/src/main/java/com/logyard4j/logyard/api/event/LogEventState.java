package com.logyard4j.logyard.api.event;

import com.logyard4j.logyard.api.Level;

import java.util.Objects;

/** Immutable implementation state behind the public {@link LogEvent} facade. */
record LogEventState(
        long timestampMillis,
        long observedTimestampUnixNanos,
        Level level,
        String loggerName,
        String eventName,
        String messageTemplate,
        Object[] arguments,
        AttributeSet attributes,
        ExceptionSnapshot exception,
        long threadId,
        String threadName,
        int remainingTraversalEntries,
        CaptureAllowance attributeAllowance,
        boolean captureTruncated,
        LazyRenderedMessage messageRenderer) {

    String renderedMessage() {
        // A missing renderer means the captured template is already a bounded literal.
        return messageRenderer == null ? Objects.requireNonNullElse(messageTemplate, "null") : messageRenderer.value();
    }

    boolean renderedMessageTruncated() {
        return messageRenderer != null && messageRenderer.truncated();
    }
}
