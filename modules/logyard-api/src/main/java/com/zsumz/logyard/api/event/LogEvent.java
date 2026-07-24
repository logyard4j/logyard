package com.zsumz.logyard.api.event;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.annotation.InternalApi;

import java.util.Objects;
import java.util.function.Supplier;

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
    private final int renderedMessageLimit;
    private final int remainingTraversalEntries;
    private volatile String renderedMessage;

    /**
     * Captures one detached, bounded event.
     *
     * @param timestampMillis source timestamp in Unix epoch milliseconds
     * @param observedTimestampUnixNanos observation timestamp in Unix epoch nanoseconds
     * @param level event level
     * @param loggerName logger name
     * @param eventName stable event name, or {@code null}
     * @param messageTemplate message template, or {@code null}
     * @param arguments positional arguments
     * @param attributes structured attributes
     * @param throwable throwable to capture, or {@code null}
     * @param threadId source thread identifier
     * @param threadName source thread name
     */
    public LogEvent(
            long timestampMillis, long observedTimestampUnixNanos, Level level, String loggerName, String eventName, String messageTemplate,
            Object[] arguments, AttributeSet attributes, Throwable throwable, long threadId, String threadName) {
        this(LogEventCapture.capture(
                timestampMillis, observedTimestampUnixNanos, level, loggerName, eventName, messageTemplate,
                () -> arguments, () -> attributes, arguments == null ? 0 : arguments.length, false,
                throwable, threadId, threadName));
    }

    /**
     * Captures deferred arguments and attributes inside the event-wide capture boundary.
     *
     * <p>This is an implementation contract for Logyard's native runtime. Suppliers are invoked
     * exactly once and only after the runtime accepts the event.</p>
     *
     * @param timestampMillis source timestamp in Unix epoch milliseconds
     * @param observedTimestampUnixNanos observation timestamp in Unix epoch nanoseconds
     * @param level event level
     * @param loggerName logger name
     * @param eventName stable event name, or {@code null}
     * @param messageTemplate message template, or {@code null}
     * @param arguments deferred retained positional arguments
     * @param attributes deferred structured attributes
     * @param suppliedArgumentCount number of positional arguments supplied before truncation
     * @param throwable throwable to capture, or {@code null}
     * @param threadId source thread identifier
     * @param threadName source thread name
     * @return detached, bounded event
     */
    @InternalApi
    public static LogEvent captureDeferred(
            long timestampMillis, long observedTimestampUnixNanos, Level level, String loggerName, String eventName, String messageTemplate,
            Supplier<Object[]> arguments, Supplier<AttributeSet> attributes, int suppliedArgumentCount,
            Throwable throwable, long threadId, String threadName) {
        return new LogEvent(LogEventCapture.capture(
                timestampMillis, observedTimestampUnixNanos, level, loggerName, eventName, messageTemplate,
                Objects.requireNonNull(arguments, "arguments"), Objects.requireNonNull(attributes, "attributes"),
                suppliedArgumentCount, true, throwable, threadId, threadName));
    }

    private LogEvent(CapturedLogEvent captured) {
        timestampMillis = captured.timestampMillis();
        observedTimestampUnixNanos = captured.observedTimestampUnixNanos();
        level = captured.level();
        loggerName = captured.loggerName();
        eventName = captured.eventName();
        messageTemplate = captured.messageTemplate();
        arguments = captured.arguments();
        attributes = captured.attributes();
        exception = captured.exception();
        threadId = captured.threadId();
        threadName = captured.threadName();
        renderedMessageLimit = captured.renderedMessageLimit();
        remainingTraversalEntries = captured.remainingTraversalEntries();
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
        renderedMessageLimit = source.renderedMessageLimit;
        remainingTraversalEntries = source.remainingTraversalEntries;
        if (Objects.equals(source.messageTemplate, messageTemplate)) {
            renderedMessage = source.renderedMessage;
        }
    }

    /**
     * Returns the source timestamp in Unix epoch milliseconds.
     *
     * @return source timestamp in Unix epoch milliseconds
     */
    public long timestampMillis() { return timestampMillis; }

    /**
     * Returns the observation timestamp in Unix epoch nanoseconds.
     *
     * @return observation timestamp in Unix epoch nanoseconds
     */
    public long observedTimestampUnixNanos() { return observedTimestampUnixNanos; }

    /**
     * Returns the observation timestamp in Unix epoch nanoseconds.
     *
     * @return observation timestamp in Unix epoch nanoseconds
     * @deprecated use {@link #observedTimestampUnixNanos()}
     */
    @Deprecated(forRemoval = false)
    public long observedNanos() { return observedTimestampUnixNanos; }

    /**
     * Returns the event level.
     *
     * @return event level
     */
    public Level level() { return level; }

    /**
     * Returns the logger name.
     *
     * @return logger name
     */
    public String loggerName() { return loggerName; }

    /**
     * Returns the stable event name, or {@code null}.
     *
     * @return event name, or {@code null}
     */
    public String eventName() { return eventName; }

    /**
     * Returns the message template, or {@code null}.
     *
     * @return message template, or {@code null}
     */
    public String messageTemplate() { return messageTemplate; }

    /**
     * Returns a copy of the captured positional arguments.
     *
     * @return copied positional arguments
     */
    public Object[] arguments() { return arguments.clone(); }

    /**
     * Returns the number of captured positional arguments.
     *
     * @return argument count
     */
    public int argumentCount() { return arguments.length; }

    /**
     * Returns one captured positional argument.
     *
     * @param index zero-based argument index
     * @return captured argument
     */
    public Object argumentAt(int index) { return arguments[index]; }

    /**
     * Returns the immutable structured attributes.
     *
     * @return structured attributes
     */
    public AttributeSet attributes() { return attributes; }

    /**
     * Returns the captured exception, or {@code null}.
     *
     * @return captured exception, or {@code null}
     */
    public ExceptionSnapshot exception() { return exception; }

    /**
     * Returns the source thread identifier.
     *
     * @return source thread identifier
     */
    public long threadId() { return threadId; }

    /**
     * Returns the source thread name.
     *
     * @return source thread name
     */
    public String threadName() { return threadName; }

    /**
     * Returns the capture budget left for trusted structured-value processors.
     *
     * @return remaining entry traversals
     */
    @InternalApi
    public int remainingTraversalEntries() { return remainingTraversalEntries; }

    /**
     * Renders the message template with the captured positional arguments.
     *
     * @return bounded rendered message
     */
    public String renderedMessage() {
        String current = renderedMessage;
        if (current == null) {
            current = MessageFormatter.format(messageTemplate, arguments, renderedMessageLimit);
            renderedMessage = current;
        }
        return current;
    }

    /**
     * Returns a copy with a replacement message template.
     *
     * @param replacement replacement template
     * @return copied event
     */
    public LogEvent withMessageTemplate(String replacement) { return new LogEvent(this, eventName, replacement, attributes); }

    /**
     * Returns a copy with a replacement event name.
     *
     * @param replacement replacement event name
     * @return copied event
     */
    public LogEvent withEventName(String replacement) { return new LogEvent(this, replacement, messageTemplate, attributes); }

    /**
     * Returns a copy with replacement attributes.
     *
     * @param replacement replacement attributes
     * @return copied event
     */
    public LogEvent withAttributes(AttributeSet replacement) { return new LogEvent(this, eventName, messageTemplate, replacement); }

    /**
     * Returns a copy enriched with optional name and template replacements plus added attributes.
     *
     * @param replacementEventName replacement event name, or {@code null} to retain the current name
     * @param replacementTemplate replacement message template, or {@code null} to retain the current template
     * @param added attributes to merge after existing attributes
     * @return enriched event
     */
    public LogEvent enrich(String replacementEventName, String replacementTemplate, AttributeSet added) {
        return new LogEvent(
                this,
                replacementEventName == null ? eventName : replacementEventName,
                replacementTemplate == null ? messageTemplate : replacementTemplate,
                attributes.mergedWith(Objects.requireNonNull(added, "added")));
    }

}
