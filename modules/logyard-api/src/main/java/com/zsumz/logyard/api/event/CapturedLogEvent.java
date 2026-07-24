package com.zsumz.logyard.api.event;

import com.zsumz.logyard.api.Level;

/** Internal value passed from the capture service to the immutable event facade. */
record CapturedLogEvent(
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
        int renderedMessageLimit,
        int remainingTraversalEntries,
        CaptureAllowance attributeAllowance,
        boolean captureTruncated) {
}
