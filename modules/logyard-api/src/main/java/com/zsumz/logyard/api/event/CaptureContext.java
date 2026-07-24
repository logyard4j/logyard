package com.zsumz.logyard.api.event;

import java.util.IdentityHashMap;
import java.util.function.Supplier;

/** One event's shared capture state, including budgets and graph identity. */
final class CaptureContext {
    private static final ThreadLocal<CaptureContext> CURRENT = new ThreadLocal<>();

    private IdentityHashMap<Object, Object> completedValues;
    private IdentityHashMap<Object, Boolean> visitingValues;
    private IdentityHashMap<Throwable, ExceptionSnapshot> completedExceptions;
    private IdentityHashMap<Throwable, Boolean> visitingExceptions;
    private int remainingNodes = CaptureLimits.MAX_EVENT_NODES;
    private int remainingEntries = CaptureLimits.MAX_EVENT_ENTRIES;
    private int remainingCharacters = CaptureLimits.MAX_EVENT_TEXT_CHARS;
    private int remainingExceptionNodes = CaptureLimits.MAX_EVENT_EXCEPTION_NODES;
    private int remainingFrames = CaptureLimits.MAX_EVENT_STACK_FRAMES;
    private boolean truncated;

    static CaptureContext create() {
        return new CaptureContext();
    }

    static CaptureContext currentOrCreate() {
        CaptureContext current = CURRENT.get();
        return current == null ? create() : current;
    }

    static <T> T within(CaptureContext context, Supplier<T> action) {
        CaptureContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    String captureText(String source, int fieldLimit) {
        if (source == null) {
            return null;
        }
        int allowance = Math.min(fieldLimit, remainingCharacters);
        String captured = CaptureLimits.truncate(source, allowance);
        remainingCharacters -= captured.length();
        if (captured.length() < source.length()) {
            truncated = true;
        }
        return captured;
    }

    int reserveRenderedMessage() {
        int reserved = Math.min(CaptureLimits.MAX_TEXT_CHARS, remainingCharacters);
        remainingCharacters -= reserved;
        return reserved;
    }

    boolean claimNode() {
        if (remainingNodes == 0) {
            truncated = true;
            return false;
        }
        remainingNodes--;
        return true;
    }

    boolean claimEntry() {
        if (remainingEntries == 0) {
            truncated = true;
            return false;
        }
        remainingEntries--;
        return true;
    }

    boolean claimExceptionNode() {
        if (remainingExceptionNodes == 0 || !claimNode()) {
            truncated = true;
            return false;
        }
        remainingExceptionNodes--;
        return true;
    }

    boolean claimFrame() {
        if (remainingFrames == 0 || !claimEntry()) {
            truncated = true;
            return false;
        }
        remainingFrames--;
        return true;
    }

    Object completedValue(Object source) {
        return completedValues == null ? null : completedValues.get(source);
    }

    void completeValue(Object source, Object captured) {
        if (completedValues == null) {
            completedValues = new IdentityHashMap<>();
        }
        completedValues.put(source, captured);
    }

    boolean visitValue(Object source) {
        if (visitingValues == null) {
            visitingValues = new IdentityHashMap<>();
        }
        return visitingValues.put(source, Boolean.TRUE) == null;
    }

    void leaveValue(Object source) {
        if (visitingValues != null) {
            visitingValues.remove(source);
        }
    }

    ExceptionSnapshot completedException(Throwable source) {
        return completedExceptions == null ? null : completedExceptions.get(source);
    }

    void completeException(Throwable source, ExceptionSnapshot captured) {
        if (completedExceptions == null) {
            completedExceptions = new IdentityHashMap<>();
        }
        completedExceptions.put(source, captured);
    }

    boolean visitException(Throwable source) {
        if (visitingExceptions == null) {
            visitingExceptions = new IdentityHashMap<>();
        }
        return visitingExceptions.put(source, Boolean.TRUE) == null;
    }

    void leaveException(Throwable source) {
        if (visitingExceptions != null) {
            visitingExceptions.remove(source);
        }
    }

    void markTruncated() {
        truncated = true;
    }

    boolean truncated() {
        return truncated;
    }

    int remainingEntries() {
        return remainingEntries;
    }

    int remainingCharacters() {
        return remainingCharacters;
    }
}
