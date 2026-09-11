package com.logyard4j.core.runtime.publication;

import com.logyard4j.api.Level;
import com.logyard4j.api.context.LogContext;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.CapturedAttributeAccess;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.ingress.IngressMetadata;

import java.time.Instant;
import java.util.Objects;

/**
 * Uncaptured event data carried from a logger to the runtime.
 *
 * <p>Clock and thread metadata are intentionally read only after the runtime confirms that the active route accepts the event.</p>
 *
 * <p>Every enabled ingress path builds a draft, so this is where the caller thread's scoped context is
 * read. Construction always runs on the thread that logged, which is what makes the read correct.</p>
 */
final class EventDraft {
    private final String loggerName;
    private final Level level;
    private final String eventName;
    private final String messageTemplate;
    private final Object[] arguments;
    private final AttributeSet attributes;
    private final PendingEventFields pendingFields;
    private final Throwable throwable;
    private final IngressMetadata ingressMetadata;
    private final AttributeSet scopedContext;

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
        pendingFields = null;
        this.throwable = throwable;
        this.ingressMetadata = Objects.requireNonNull(ingressMetadata, "ingressMetadata");
        scopedContext = LogContext.current();
    }

    EventDraft(
            String loggerName,
            Level level,
            String eventName,
            String messageTemplate,
            PendingEventFields pendingFields,
            Throwable throwable,
            IngressMetadata ingressMetadata) {
        this.loggerName = Objects.requireNonNull(loggerName, "loggerName");
        this.level = Objects.requireNonNull(level, "level");
        this.eventName = eventName;
        this.messageTemplate = messageTemplate;
        arguments = null;
        attributes = null;
        this.pendingFields = Objects.requireNonNull(pendingFields, "pendingFields");
        this.throwable = throwable;
        this.ingressMetadata = Objects.requireNonNull(ingressMetadata, "ingressMetadata");
        scopedContext = LogContext.current();
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

        if (pendingFields != null) {
            return LogEvent.captureDeferred(
                    timestampMillis,
                    unixNanos(observedAt),
                    level,
                    loggerName,
                    eventName,
                    messageTemplate,
                    pendingFields::captureArguments,
                    this::captureAttributes,
                    pendingFields.suppliedArgumentCount(),
                    throwable,
                    threadId,
                    threadName);
        }
        return new LogEvent(
                timestampMillis,
                unixNanos(observedAt),
                level,
                loggerName,
                eventName,
                messageTemplate,
                arguments,
                withScopedContext(attributes),
                throwable,
                threadId,
                threadName);
    }

    private AttributeSet captureAttributes() {
        return pendingFields.captureAttributes(scopedContext);
    }

    private AttributeSet withScopedContext(AttributeSet eventAttributes) {
        if (scopedContext.isEmpty() && !CapturedAttributeAccess.truncated(scopedContext)) {
            return eventAttributes;
        }
        return eventAttributes == null ? scopedContext : scopedContext.mergedWith(eventAttributes);
    }

    private static long unixNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }
}
