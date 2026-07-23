package com.zsumz.logyard.api.event;

import com.zsumz.logyard.api.Level;
import java.util.Objects;

/** One captured event. Templates and arguments stay separate until an output renders them. */
public final class LogEvent {
    private final long timestampMillis;
    private final long observedTimestampUnixNanos;
    private final Level level;
    private final String loggerName;
    private final String eventName;
    private final String messageTemplate;
    private final Object[] arguments;
    private final AttributeSet attributes;
    private final ExceptionSnapshot exception;
    private final long threadId;
    private final String threadName;

    public LogEvent(
            long timestampMillis,
            long observedTimestampUnixNanos,
            Level level,
            String loggerName,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable,
            long threadId,
            String threadName) {
        this.timestampMillis = timestampMillis;
        this.observedTimestampUnixNanos = observedTimestampUnixNanos;
        this.level = Objects.requireNonNull(level, "level");
        this.loggerName = CaptureLimits.name(Objects.requireNonNull(loggerName, "loggerName"));
        this.eventName = CaptureLimits.name(eventName);
        this.messageTemplate = CaptureLimits.text(messageTemplate);
        int suppliedArgumentCount = arguments == null ? 0 : arguments.length;
        this.arguments = ValueCapture.arguments(arguments);
        AttributeSet baseAttributes = attributes == null ? AttributeSet.EMPTY : attributes;
        this.attributes = suppliedArgumentCount > CaptureLimits.MAX_ARGUMENTS
                ? baseAttributes.withSystemAttribute(
                        "logyard.arguments.omitted",
                        suppliedArgumentCount - CaptureLimits.MAX_ARGUMENTS)
                : baseAttributes;
        this.exception = ExceptionSnapshot.capture(throwable);
        this.threadId = threadId;
        this.threadName = CaptureLimits.name(Objects.requireNonNullElse(threadName, "unknown"));
    }

    private LogEvent(LogEvent source, String eventName, String template, AttributeSet attributes) {
        timestampMillis = source.timestampMillis;
        observedTimestampUnixNanos = source.observedTimestampUnixNanos;
        level = source.level;
        loggerName = source.loggerName;
        this.eventName = CaptureLimits.name(eventName);
        messageTemplate = CaptureLimits.text(template);
        arguments = source.arguments;
        this.attributes = Objects.requireNonNull(attributes, "attributes");
        exception = source.exception;
        threadId = source.threadId;
        threadName = source.threadName;
    }

    public long timestampMillis() { return timestampMillis; }
    public long observedTimestampUnixNanos() { return observedTimestampUnixNanos; }

    /** @deprecated use {@link #observedTimestampUnixNanos()} */
    @Deprecated(forRemoval = false)
    public long observedNanos() { return observedTimestampUnixNanos; }
    public Level level() { return level; }
    public String loggerName() { return loggerName; }
    public String eventName() { return eventName; }
    public String messageTemplate() { return messageTemplate; }
    public Object[] arguments() { return arguments.clone(); }
    public int argumentCount() { return arguments.length; }
    public Object argumentAt(int index) { return arguments[index]; }
    public AttributeSet attributes() { return attributes; }
    public ExceptionSnapshot exception() { return exception; }
    public long threadId() { return threadId; }
    public String threadName() { return threadName; }

    public String renderedMessage() {
        return MessageFormatter.format(messageTemplate, arguments);
    }

    public LogEvent withMessageTemplate(String replacement) {
        return new LogEvent(this, eventName, replacement, attributes);
    }

    public LogEvent withEventName(String replacement) {
        return new LogEvent(this, replacement, messageTemplate, attributes);
    }

    public LogEvent withAttributes(AttributeSet replacement) {
        return new LogEvent(this, eventName, messageTemplate, replacement);
    }

    public LogEvent enrich(String replacementEventName, String replacementTemplate, AttributeSet added) {
        return new LogEvent(
                this,
                replacementEventName == null ? eventName : replacementEventName,
                replacementTemplate == null ? messageTemplate : replacementTemplate,
                attributes.mergedWith(Objects.requireNonNull(added, "added")));
    }
}
