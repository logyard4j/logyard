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

    static LogEventState from(CapturedLogEvent captured) {
        return new LogEventState(
                captured.timestampMillis(),
                captured.observedTimestampUnixNanos(),
                captured.level(),
                captured.loggerName(),
                captured.eventName(),
                captured.messageTemplate(),
                captured.arguments(),
                captured.attributes(),
                captured.exception(),
                captured.threadId(),
                captured.threadName(),
                captured.remainingTraversalEntries(),
                captured.attributeAllowance(),
                captured.captureTruncated(),
                new LazyRenderedMessage(captured.messageTemplate(), captured.arguments(), captured.renderedMessageLimit()));
    }
}
