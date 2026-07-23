package com.zsumz.logyard.api.event;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded copy of a {@link Throwable} graph.
 *
 * <p>The snapshot deliberately does not retain the application exception, so asynchronous
 * delivery cannot keep an arbitrary object graph alive after the logging call returns.</p>
 */
public final class ExceptionSnapshot {
    public static final int MAX_CAUSE_DEPTH = 8;
    public static final int MAX_SUPPRESSED_PER_NODE = 8;
    public static final int MAX_FRAMES_PER_NODE = 256;
    public static final int MAX_MESSAGE_CHARS = 16_384;

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

    public static ExceptionSnapshot capture(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        return capture(throwable, new IdentityHashMap<>(), 0);
    }

    public String type() {
        return type;
    }

    public String message() {
        return message;
    }

    public List<StackTraceElement> frames() {
        return frames;
    }

    public List<ExceptionSnapshot> suppressed() {
        return suppressed;
    }

    public ExceptionSnapshot cause() {
        return cause;
    }

    public boolean truncated() {
        return truncated;
    }

    public String summary() {
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    private static ExceptionSnapshot capture(
            Throwable throwable,
            IdentityHashMap<Throwable, Boolean> visiting,
            int depth) {
        if (depth >= MAX_CAUSE_DEPTH) {
            return marker("[maximum cause depth reached]");
        }
        if (visiting.put(throwable, Boolean.TRUE) != null) {
            return marker("[circular exception reference]");
        }
        try {
            boolean truncated = false;
            String type = throwable.getClass().getName();
            String message;
            try {
                String sourceMessage = throwable.getMessage();
                message = truncate(sourceMessage, MAX_MESSAGE_CHARS);
                if (safeLength(sourceMessage) > MAX_MESSAGE_CHARS) {
                    truncated = true;
                }
            } catch (Throwable failure) {
                rethrowIfFatal(failure);
                message = "[message accessor failed: " + failure.getClass().getName() + ']';
                truncated = true;
            }

            StackTraceElement[] sourceFrames;
            try {
                sourceFrames = throwable.getStackTrace();
                if (sourceFrames == null) {
                    sourceFrames = new StackTraceElement[0];
                }
            } catch (Throwable failure) {
                rethrowIfFatal(failure);
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
                StackTraceElement frame = sourceFrames[index];
                if (frame != null) {
                    frames.add(frame);
                }
            }
            if (sourceFrames.length > frameCount) {
                truncated = true;
            }

            Throwable[] sourceSuppressed;
            try {
                sourceSuppressed = throwable.getSuppressed();
                if (sourceSuppressed == null) {
                    sourceSuppressed = new Throwable[0];
                }
            } catch (Throwable failure) {
                rethrowIfFatal(failure);
                sourceSuppressed = new Throwable[0];
                truncated = true;
            }
            int suppressedCount = Math.min(sourceSuppressed.length, MAX_SUPPRESSED_PER_NODE);
            List<ExceptionSnapshot> suppressed = new ArrayList<>(suppressedCount);
            for (int index = 0; index < suppressedCount; index++) {
                Throwable current = sourceSuppressed[index];
                if (current != null) {
                    suppressed.add(capture(current, visiting, depth + 1));
                }
            }
            if (sourceSuppressed.length > suppressedCount) {
                truncated = true;
            }

            Throwable sourceCause;
            try {
                sourceCause = throwable.getCause();
            } catch (Throwable failure) {
                rethrowIfFatal(failure);
                sourceCause = null;
                truncated = true;
            }
            ExceptionSnapshot cause = sourceCause == null || sourceCause == throwable
                    ? null
                    : capture(sourceCause, visiting, depth + 1);
            if (cause != null && cause.truncated()) {
                truncated = true;
            }
            for (ExceptionSnapshot current : suppressed) {
                if (current.truncated()) {
                    truncated = true;
                    break;
                }
            }

            return new ExceptionSnapshot(type, message, frames, suppressed, cause, truncated);
        } finally {
            visiting.remove(throwable);
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

    private static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        int prefixLength = Math.max(0, maximum - 1);
        if (prefixLength > 0 && prefixLength < value.length()
                && Character.isHighSurrogate(value.charAt(prefixLength - 1))
                && Character.isLowSurrogate(value.charAt(prefixLength))) {
            prefixLength--;
        }
        return value.substring(0, prefixLength) + "…";
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
