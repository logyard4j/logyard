package com.logyard4j.api.event;

import com.logyard4j.api.Level;

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
        LazyRenderedMessage renderedMessage) {
}
