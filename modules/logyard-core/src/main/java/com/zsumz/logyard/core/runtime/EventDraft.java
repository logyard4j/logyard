package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.ingress.IngressMetadata;

import java.time.Instant;
import java.util.Objects;

/**
 * Uncaptured event data carried from a logger to the runtime.
 *
 * <p>Clock and thread metadata are intentionally read only after the runtime confirms that the active route accepts the event.</p>
 */
final class EventDraft {
    private final String loggerName;
    private final Level level;
    private final String eventName;
    private final String messageTemplate;
    private final Object[] arguments;
    private final AttributeSet attributes;
    private final Throwable throwable;
    private final IngressMetadata ingressMetadata;

    EventDraft(
            String loggerName,
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable,
            IngressMetadata ingressMetadata) {
        this.loggerName = Objects.requireNonNull(loggerName, "loggerName");
        this.level = Objects.requireNonNull(level, "level");
        this.eventName = eventName;
        this.messageTemplate = messageTemplate;
        this.arguments = arguments;
        this.attributes = attributes;
        this.throwable = throwable;
        this.ingressMetadata = Objects.requireNonNull(ingressMetadata, "ingressMetadata");
    }

    String loggerName() {
        return loggerName;
    }

    Level level() {
        return level;
    }

    String messageTemplate() {
        return messageTemplate;
    }

    LogEvent capture() {
        Thread currentThread = Thread.currentThread();
        Instant observedAt = Instant.now();
        long timestampMillis = ingressMetadata.hasSourceTimestamp()
                ? ingressMetadata.sourceTimestampMillis()
                : observedAt.toEpochMilli();
        long threadId = ingressMetadata.hasSourceThreadId()
                ? ingressMetadata.sourceThreadId()
                : ingressMetadata.sourceThreadName() == null ? currentThread.threadId() : -1L;
        String threadName = ingressMetadata.sourceThreadName() == null ? currentThread.getName() : ingressMetadata.sourceThreadName();

        return new LogEvent(
                timestampMillis,
                unixNanos(observedAt),
                level,
                loggerName,
                eventName,
                messageTemplate,
                arguments,
                attributes,
                throwable,
                threadId,
                threadName);
    }

    private static long unixNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }
}
