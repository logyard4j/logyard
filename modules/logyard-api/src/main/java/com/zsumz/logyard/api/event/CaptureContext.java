package com.zsumz.logyard.api.event;

import java.util.IdentityHashMap;
import java.util.function.Supplier;

/** One event's shared capture state, including partitioned budgets and graph identity. */
final class CaptureContext {
    private static final ThreadLocal<CaptureContext> CURRENT = new ThreadLocal<>();

    private IdentityHashMap<Object, Object> capturedScalars;
    private IdentityHashMap<Object, Boolean> seenValues;
    private IdentityHashMap<Object, Boolean> visitingValues;
    private IdentityHashMap<Throwable, Boolean> seenExceptions;
    private IdentityHashMap<Throwable, Boolean> visitingExceptions;
    private int remainingNodes;
    private int remainingEntries;
    private int remainingPayloadCharacters;
    private int remainingExceptionTypeCharacters;
    private int remainingExceptionMessageCharacters;
    private int remainingExceptionFrameCharacters;
    private int remainingIdentityCharacters;
    private int remainingTemplateCharacters;
    private int remainingExceptionNodes;
    private int remainingFrames;
    private boolean truncated;

    private CaptureContext(
            int nodes,
            int entries,
            int payloadCharacters,
            int exceptionTypeCharacters,
            int exceptionMessageCharacters,
            int exceptionFrameCharacters,
            int identityCharacters,
            int templateCharacters,
            int exceptionNodes,
            int frames) {
        remainingNodes = nodes;
        remainingEntries = entries;
        remainingPayloadCharacters = payloadCharacters;
        remainingExceptionTypeCharacters = exceptionTypeCharacters;
        remainingExceptionMessageCharacters = exceptionMessageCharacters;
        remainingExceptionFrameCharacters = exceptionFrameCharacters;
        remainingIdentityCharacters = identityCharacters;
        remainingTemplateCharacters = templateCharacters;
        remainingExceptionNodes = exceptionNodes;
        remainingFrames = frames;
    }

    static CaptureContext create() {
        return new CaptureContext(
                CaptureLimits.MAX_EVENT_NODES,
                CaptureLimits.MAX_EVENT_ENTRIES,
                CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS,
                CaptureLimits.MAX_EVENT_EXCEPTION_TYPE_CHARS,
                CaptureLimits.MAX_EVENT_EXCEPTION_MESSAGE_CHARS,
                CaptureLimits.MAX_EVENT_EXCEPTION_FRAME_CHARS,
                CaptureLimits.MAX_EVENT_IDENTITY_CHARS,
                CaptureLimits.MAX_EVENT_TEMPLATE_CHARS,
                CaptureLimits.MAX_EVENT_EXCEPTION_NODES,
                CaptureLimits.MAX_EVENT_STACK_FRAMES);
    }

    static CaptureContext forAttributes(CaptureAllowance allowance) {
        return new CaptureContext(
                allowance.nodes(),
                allowance.entries(),
                allowance.characters(),
                0,
                0,
                0,
                0,
                0,
                0,
                0);
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

    String capturePayloadText(String source, int fieldLimit) {
        CapturedText captured = captureText(source, fieldLimit, remainingPayloadCharacters);
        remainingPayloadCharacters -= length(captured.value());
        observe(captured);
        return captured.value();
    }

    String captureIdentityText(String source, int fieldLimit) {
        CapturedText captured = captureText(source, fieldLimit, remainingIdentityCharacters);
        remainingIdentityCharacters -= length(captured.value());
        observe(captured);
        return captured.value();
    }

    String captureTemplateText(String source, int fieldLimit) {
        CapturedText captured = captureText(source, fieldLimit, remainingTemplateCharacters);
        remainingTemplateCharacters -= length(captured.value());
        observe(captured);
        return captured.value();
    }

    CapturedText captureExceptionTypeText(String source, int fieldLimit) {
        CapturedText captured = captureText(source, fieldLimit, remainingExceptionTypeCharacters);
        remainingExceptionTypeCharacters -= length(captured.value());
        observe(captured);
        return captured;
    }

    CapturedText captureExceptionMessageText(String source, int fieldLimit) {
        CapturedText captured = captureText(source, fieldLimit, remainingExceptionMessageCharacters);
        remainingExceptionMessageCharacters -= length(captured.value());
        observe(captured);
        return captured;
    }

    CapturedText captureExceptionFrameText(String source, int fieldLimit) {
        CapturedText captured = captureText(source, fieldLimit, remainingExceptionFrameCharacters);
        remainingExceptionFrameCharacters -= length(captured.value());
        observe(captured);
        return captured;
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

    Object capturedScalar(Object source) {
        return capturedScalars == null ? null : capturedScalars.get(source);
    }

    void completeScalar(Object source, Object captured) {
        if (capturedScalars == null) {
            capturedScalars = new IdentityHashMap<>();
        }
        capturedScalars.put(source, captured);
    }

    ReferenceState enterValue(Object source) {
        if (visitingValues != null && visitingValues.containsKey(source)) {
            return ReferenceState.CYCLE;
        }
        if (seenValues != null && seenValues.containsKey(source)) {
            return ReferenceState.SHARED;
        }
        if (seenValues == null) {
            seenValues = new IdentityHashMap<>();
            visitingValues = new IdentityHashMap<>();
        }
        seenValues.put(source, Boolean.TRUE);
        visitingValues.put(source, Boolean.TRUE);
        return ReferenceState.FRESH;
    }

    void leaveValue(Object source) {
        visitingValues.remove(source);
    }

    ReferenceState enterException(Throwable source) {
        if (visitingExceptions != null && visitingExceptions.containsKey(source)) {
            return ReferenceState.CYCLE;
        }
        if (seenExceptions != null && seenExceptions.containsKey(source)) {
            return ReferenceState.SHARED;
        }
        if (seenExceptions == null) {
            seenExceptions = new IdentityHashMap<>();
            visitingExceptions = new IdentityHashMap<>();
        }
        seenExceptions.put(source, Boolean.TRUE);
        visitingExceptions.put(source, Boolean.TRUE);
        return ReferenceState.FRESH;
    }

    void leaveException(Throwable source) {
        visitingExceptions.remove(source);
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

    int remainingPayloadCharacters() {
        return remainingPayloadCharacters;
    }

    boolean canCaptureExceptionFrame() {
        return remainingExceptionFrameCharacters > 0;
    }

    CaptureAllowance payloadAllowance() {
        return new CaptureAllowance(remainingNodes, remainingEntries, remainingPayloadCharacters);
    }

    private static CapturedText captureText(String source, int fieldLimit, int remaining) {
        if (source == null) {
            return new CapturedText(null, false);
        }
        int allowance = Math.min(fieldLimit, remaining);
        String captured = CaptureLimits.truncate(source, allowance);
        return new CapturedText(captured, captured.length() < source.length());
    }

    private void observe(CapturedText captured) {
        if (captured.truncated()) {
            truncated = true;
        }
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    enum ReferenceState {
        FRESH,
        CYCLE,
        SHARED
    }
}
