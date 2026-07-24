package com.zsumz.logyard.api.event;

import com.zsumz.logyard.api.failure.FailureIsolation;

import java.util.ArrayList;
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

    private ExceptionSnapshot(
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
        if (throwable == null) {
            return null;
        }
        return capture(throwable, CaptureContext.currentOrCreate(), 0);
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
     * Returns a single-line type-and-message summary.
     *
     * @return exception summary
     */
    public String summary() {
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    static ExceptionSnapshot capture(Throwable throwable, CaptureContext context) {
        return throwable == null ? null : capture(throwable, context, 0);
    }

    private static ExceptionSnapshot capture(Throwable throwable, CaptureContext context, int depth) {
        if (depth >= MAX_CAUSE_DEPTH) {
            context.markTruncated();
            return marker("[maximum cause depth reached]");
        }
        CaptureContext.ReferenceState reference = context.enterException(throwable);
        if (reference == CaptureContext.ReferenceState.CYCLE) {
            context.markTruncated();
            return marker("[circular exception reference]");
        }
        if (reference == CaptureContext.ReferenceState.SHARED) {
            context.markTruncated();
            return marker("[shared exception reference]");
        }
        if (!context.claimExceptionNode()) {
            context.leaveException(throwable);
            return marker("[event exception budget exhausted]");
        }
        try {
            boolean truncated = false;
            CapturedText capturedType = context.captureExceptionTypeText(
                    throwable.getClass().getName(),
                    CaptureLimits.MAX_NAME_CHARS);
            String type = capturedType.value();
            if (type == null || type.isBlank()) {
                type = "[exception type omitted]";
                truncated = true;
                context.markTruncated();
            } else {
                truncated = capturedType.truncated();
            }
            String message;
            try {
                CapturedText capturedMessage = context.captureExceptionMessageText(
                        throwable.getMessage(),
                        MAX_MESSAGE_CHARS);
                message = capturedMessage.value();
                truncated |= capturedMessage.truncated();
            } catch (Throwable failure) {
                FailureIsolation.prepareForRecovery(failure);
                CapturedText failedMessage = context.captureExceptionMessageText(
                        "[message accessor failed: " + failure.getClass().getName() + ']',
                        MAX_MESSAGE_CHARS);
                message = failedMessage.value();
                truncated = true;
            }

            StackTraceElement[] sourceFrames;
            try {
                sourceFrames = throwable.getStackTrace();
                if (sourceFrames == null) {
                    sourceFrames = new StackTraceElement[0];
                }
            } catch (Throwable failure) {
                FailureIsolation.prepareForRecovery(failure);
                sourceFrames = new StackTraceElement[] {
                        new StackTraceElement(
                                "logyard.exception",
                                "unavailable",
                                "stack trace accessor failed: " + failure.getClass().getName(),
                                -1)
                };
                truncated = true;
            }
            int frameCount = Math.min(sourceFrames.length, MAX_FRAMES_PER_NODE);
            List<StackTraceElement> frames = new ArrayList<>(frameCount);
            for (int index = 0; index < frameCount; index++) {
                if (!context.canCaptureExceptionFrame() || !context.claimFrame()) {
                    truncated = true;
                    context.markTruncated();
                    break;
                }
                StackTraceElement frame = sourceFrames[index];
                if (frame != null) {
                    ExceptionFrameCapture.Result capturedFrame = ExceptionFrameCapture.capture(frame, context);
                    frames.add(capturedFrame.frame());
                    truncated |= capturedFrame.truncated();
                }
            }
            if (sourceFrames.length > frames.size()) {
                truncated = true;
                context.markTruncated();
            }

            Throwable sourceCause;
            try {
                sourceCause = throwable.getCause();
            } catch (Throwable failure) {
                FailureIsolation.prepareForRecovery(failure);
                sourceCause = null;
                truncated = true;
            }
            ExceptionSnapshot cause = null;
            if (sourceCause == throwable) {
                truncated = true;
                context.markTruncated();
            } else if (sourceCause != null) {
                cause = capture(sourceCause, context, depth + 1);
            }
            if (cause != null && cause.truncated()) {
                truncated = true;
            }

            Throwable[] sourceSuppressed;
            try {
                sourceSuppressed = throwable.getSuppressed();
                if (sourceSuppressed == null) {
                    sourceSuppressed = new Throwable[0];
                }
            } catch (Throwable failure) {
                FailureIsolation.prepareForRecovery(failure);
                sourceSuppressed = new Throwable[0];
                truncated = true;
            }
            int suppressedCount = Math.min(sourceSuppressed.length, MAX_SUPPRESSED_PER_NODE);
            List<ExceptionSnapshot> suppressed = new ArrayList<>(suppressedCount);
            for (int index = 0; index < suppressedCount; index++) {
                if (!context.claimEntry()) {
                    truncated = true;
                    break;
                }
                Throwable current = sourceSuppressed[index];
                if (current != null) {
                    suppressed.add(capture(current, context, depth + 1));
                }
            }
            if (sourceSuppressed.length > suppressed.size()) {
                truncated = true;
                context.markTruncated();
            }

            for (ExceptionSnapshot current : suppressed) {
                if (current.truncated()) {
                    truncated = true;
                    break;
                }
            }

            return new ExceptionSnapshot(type, message, frames, suppressed, cause, truncated);
        } finally {
            context.leaveException(throwable);
        }
    }

    private static ExceptionSnapshot marker(String message) {
        return new ExceptionSnapshot(
                "com.zsumz.logyard.api.event.ExceptionSnapshot",
                message,
                List.of(),
                List.of(),
                null,
                true);
    }

}
