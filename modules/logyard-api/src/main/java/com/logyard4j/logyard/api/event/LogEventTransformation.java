package com.logyard4j.logyard.api.event;

import java.util.Objects;

/** Applies trusted immutable replacements while preserving an event's original capture allowance. */
final class LogEventTransformation {
    private LogEventTransformation() {
    }

    static LogEventState replace(
            LogEventState source,
            String replacementEventName,
            String replacementTemplate,
            AttributeSet replacementAttributes) {
        String eventName = CaptureLimits.name(replacementEventName);
        String messageTemplate = CaptureLimits.truncate(replacementTemplate, CaptureLimits.MAX_EVENT_TEMPLATE_CHARS);
        boolean inheritedTruncation = source.captureTruncated()
                || shortened(replacementEventName, eventName)
                || shortened(replacementTemplate, messageTemplate);
        EventAttributeCapture.Result attributes = attributes(source, replacementAttributes, inheritedTruncation);
        boolean sameTemplate = Objects.equals(source.messageTemplate(), messageTemplate);
        if (sameTemplate) messageTemplate = source.messageTemplate();
        LazyRenderedMessage messageRenderer = sameTemplate
                ? source.messageRenderer()
                : LazyRenderedMessage.forEvent(messageTemplate, source.arguments());
        return new LogEventState(
                source.timestampMillis(),
                source.observedTimestampUnixNanos(),
                source.level(),
                source.loggerName(),
                eventName,
                messageTemplate,
                source.arguments(),
                attributes.attributes(),
                source.exception(),
                source.threadId(),
                source.threadName(),
                attributes.remainingTraversalEntries(),
                source.attributeAllowance(),
                attributes.truncated(),
                messageRenderer);
    }

    private static EventAttributeCapture.Result attributes(
            LogEventState source,
            AttributeSet replacement,
            boolean inheritedTruncation) {
        if (replacement != null) {
            return EventAttributeCapture.capture(replacement, source.attributeAllowance(), inheritedTruncation);
        }
        AttributeSet attributes = inheritedTruncation
                ? source.attributes().withSystemAttribute(SystemAttributes.CAPTURE_TRUNCATED, true)
                : source.attributes();
        return new EventAttributeCapture.Result(attributes, source.remainingTraversalEntries(), inheritedTruncation);
    }

    private static boolean shortened(String source, String captured) {
        return source != null && source.length() > Objects.requireNonNullElse(captured, "").length();
    }
}
