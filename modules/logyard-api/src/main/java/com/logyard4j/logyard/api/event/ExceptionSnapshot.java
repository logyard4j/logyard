package com.logyard4j.logyard.api.event;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded copy of a {@link Throwable} graph.
 *
 * <p>The snapshot deliberately does not retain the application exception, so asynchronous
 * delivery cannot keep an arbitrary object graph alive after the logging call returns.</p>
 */
public final class ExceptionSnapshot {
    /** Maximum number of linked causes captured from a throwable graph. */
    public static final int MAX_CAUSE_DEPTH = 8;

    /** Maximum number of suppressed exceptions captured at each node. */
    public static final int MAX_SUPPRESSED_PER_NODE = 8;

    /** Maximum number of stack frames captured at each node. */
    public static final int MAX_FRAMES_PER_NODE = 256;

    /** Maximum UTF-16 characters retained from an exception message. */
    public static final int MAX_MESSAGE_CHARS = CaptureLimits.MAX_EVENT_EXCEPTION_MESSAGE_CHARS;

    private final String type;
    private final String message;
    private final List<StackTraceElement> frames;
    private final List<ExceptionSnapshot> suppressed;
    private final ExceptionSnapshot cause;
    private final boolean truncated;

    ExceptionSnapshot(
            String type,
            String message,
            List<StackTraceElement> frames,
            List<ExceptionSnapshot> suppressed,
            ExceptionSnapshot cause,
            boolean truncated) {
        this.type = Objects.requireNonNull(type, "type");
        this.message = message;
        this.frames = List.copyOf(frames);
        this.suppressed = List.copyOf(suppressed);
        this.cause = cause;
        this.truncated = truncated;
    }

    /**
     * Captures a detached, bounded throwable graph.
     *
     * @param throwable throwable to capture, or {@code null}
     * @return immutable snapshot, or {@code null} for a null throwable
     */
    public static ExceptionSnapshot capture(Throwable throwable) {
        return ExceptionSnapshotCapture.capture(throwable, CaptureContext.currentOrCreate());
    }

    /**
     * Returns the fully qualified throwable type name.
     *
     * @return throwable type name
     */
    public String type() {
        return type;
    }

    /**
     * Returns the captured exception message, or {@code null}.
     *
     * @return exception message, or {@code null}
     */
    public String message() {
        return message;
    }

    /**
     * Returns the captured stack frames in source order.
     *
     * @return immutable stack frames
     */
    public List<StackTraceElement> frames() {
        return frames;
    }

    /**
     * Returns captured suppressed exceptions in source order.
     *
     * @return immutable suppressed exceptions
     */
    public List<ExceptionSnapshot> suppressed() {
        return suppressed;
    }

    /**
     * Returns the captured cause, or {@code null}.
     *
     * @return captured cause, or {@code null}
     */
    public ExceptionSnapshot cause() {
        return cause;
    }

    /**
     * Returns whether any part of this throwable graph was truncated or unavailable.
     *
     * @return {@code true} when capture was incomplete
     */
    public boolean truncated() {
        return truncated;
    }

    /**
     * Returns a type-and-message summary, retaining the captured message text.
     *
     * @return exception summary
     */
    public String summary() {
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    static ExceptionSnapshot capture(Throwable throwable, CaptureContext context) {
        return ExceptionSnapshotCapture.capture(throwable, context);
    }
}
