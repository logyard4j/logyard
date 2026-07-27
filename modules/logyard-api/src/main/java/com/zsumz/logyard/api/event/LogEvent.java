package com.zsumz.logyard.api.event;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.annotation.InternalApi;

import java.util.Objects;
import java.util.function.Supplier;

/** One detached, bounded event. Templates and arguments remain separate until an output renders them. */
public final class LogEvent {
    private final LogEventState state;

    /** Captures a detached bounded event.
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
        this(LogEventState.from(LogEventCapture.capture(
                timestampMillis, observedTimestampUnixNanos, level, loggerName, eventName, messageTemplate,
                () -> arguments, () -> attributes, arguments == null ? 0 : arguments.length, false, throwable, threadId, threadName)));
    }

    /**
     * Captures deferred values only after the runtime accepts the event.
     * @param timestampMillis source timestamp in Unix epoch milliseconds
     * @param observedTimestampUnixNanos observation timestamp in Unix epoch nanoseconds
     * @param level event level
     * @param loggerName logger name
     * @param eventName stable event name, or {@code null}
     * @param messageTemplate message template, or {@code null}
     * @param arguments deferred positional arguments
     * @param attributes deferred structured attributes
     * @param suppliedArgumentCount supplied positional argument count before truncation
     * @param throwable throwable to capture, or {@code null}
     * @param threadId source thread identifier
     * @param threadName source thread name
     * @return detached bounded event
     */
    @InternalApi
    public static LogEvent captureDeferred(
            long timestampMillis, long observedTimestampUnixNanos, Level level, String loggerName, String eventName, String messageTemplate,
            Supplier<Object[]> arguments, Supplier<AttributeSet> attributes, int suppliedArgumentCount,
            Throwable throwable, long threadId, String threadName) {
        return new LogEvent(LogEventState.from(LogEventCapture.capture(
                timestampMillis, observedTimestampUnixNanos, level, loggerName, eventName, messageTemplate,
                Objects.requireNonNull(arguments, "arguments"), Objects.requireNonNull(attributes, "attributes"),
                suppliedArgumentCount, true, throwable, threadId, threadName)));
    }

    private LogEvent(LogEventState state) { this.state = state; }

    /** Returns the source timestamp.
     * @return source timestamp in Unix epoch milliseconds
     */
    public long timestampMillis() { return state.timestampMillis(); }
    /** Returns the observation timestamp.
     * @return observation timestamp in Unix epoch nanoseconds
     */
    public long observedTimestampUnixNanos() { return state.observedTimestampUnixNanos(); }
    /**
     * Returns the observation timestamp.
     * @return observation timestamp in Unix epoch nanoseconds
     * @deprecated use {@link #observedTimestampUnixNanos()}
     */
    @Deprecated(forRemoval = false)
    public long observedNanos() { return observedTimestampUnixNanos(); }
    /** Returns the event level.
     * @return event level
     */
    public Level level() { return state.level(); }
    /** Returns the logger name.
     * @return logger name
     */
    public String loggerName() { return state.loggerName(); }
    /** Returns the stable event name.
     * @return stable event name, or {@code null}
     */
    public String eventName() { return state.eventName(); }
    /** Returns the message template.
     * @return message template, or {@code null}
     */
    public String messageTemplate() { return state.messageTemplate(); }
    /** Returns copied positional arguments.
     * @return copy of captured positional arguments
     */
    public Object[] arguments() { return state.arguments().clone(); }
    /** Returns the argument count.
     * @return captured positional argument count
     */
    public int argumentCount() { return state.arguments().length; }
    /** Returns a captured positional argument.
     * @param index zero-based argument index
     * @return captured argument
     */
    public Object argumentAt(int index) { return state.arguments()[index]; }
    /** Returns immutable structured attributes.
     * @return immutable structured attributes
     */
    public AttributeSet attributes() { return state.attributes(); }
    /** Returns the captured exception.
     * @return captured exception, or {@code null}
     */
    public ExceptionSnapshot exception() { return state.exception(); }
    /** Returns the source thread identifier.
     * @return source thread identifier
     */
    public long threadId() { return state.threadId(); }
    /** Returns the source thread name.
     * @return source thread name
     */
    public String threadName() { return state.threadName(); }
    /** Returns the capture budget left for trusted processors.
     * @return capture budget left for trusted structured-value processors
     */
    @InternalApi
    public int remainingTraversalEntries() { return state.remainingTraversalEntries(); }
    /** Renders the message template.
     * @return rendered message
     */
    public String renderedMessage() { return state.renderedMessage().value(); }
    /** Reports whether rendering exceeded its character allowance.
     * @return whether rendered message exceeded its character allowance
     */
    public boolean renderedMessageTruncated() { return state.renderedMessage().truncated(); }
    /** Replaces the message template.
     * @param replacement replacement message template
     * @return copied event
     */
    public LogEvent withMessageTemplate(String replacement) { return replace(state.eventName(), replacement, null); }
    /** Replaces the event name.
     * @param replacement replacement event name
     * @return copied event
     */
    public LogEvent withEventName(String replacement) { return replace(replacement, state.messageTemplate(), null); }
    /** Replaces structured attributes.
     * @param replacement replacement attributes
     * @return copied event
     */
    public LogEvent withAttributes(AttributeSet replacement) { return replace(state.eventName(), state.messageTemplate(), replacement); }
    /** Enriches this event with optional name/template replacements and added attributes.
     * @param replacementEventName replacement event name, or {@code null}
     * @param replacementTemplate replacement message template, or {@code null}
     * @param added attributes to merge
     * @return enriched event
     */
    public LogEvent enrich(String replacementEventName, String replacementTemplate, AttributeSet added) {
        return replace(
                replacementEventName == null ? state.eventName() : replacementEventName,
                replacementTemplate == null ? state.messageTemplate() : replacementTemplate,
                state.attributes().mergedWith(Objects.requireNonNull(added, "added")));
    }

    private LogEvent replace(String eventName, String messageTemplate, AttributeSet attributes) {
        return new LogEvent(LogEventTransformation.replace(state, eventName, messageTemplate, attributes));
    }
}
