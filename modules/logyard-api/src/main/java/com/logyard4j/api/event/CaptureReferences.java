package com.logyard4j.api.event;

import java.util.IdentityHashMap;

/** Identity-based graph bookkeeping shared by the value and exception capture paths of one event. */
final class CaptureReferences {
    private IdentityHashMap<Object, Object> capturedScalars;
    private IdentityHashMap<Object, Boolean> seenValues;
    private IdentityHashMap<Object, Boolean> visitingValues;
    private IdentityHashMap<Throwable, Boolean> seenExceptions;
    private IdentityHashMap<Throwable, Boolean> visitingExceptions;

    Object capturedScalar(Object source) {
        return capturedScalars == null ? null : capturedScalars.get(source);
    }

    void completeScalar(Object source, Object captured) {
        if (capturedScalars == null) {
            capturedScalars = new IdentityHashMap<>();
        }
        capturedScalars.put(source, captured);
    }

    CaptureContext.ReferenceState enterValue(Object source) {
        if (visitingValues != null && visitingValues.containsKey(source)) {
            return CaptureContext.ReferenceState.CYCLE;
        }
        if (seenValues != null && seenValues.containsKey(source)) {
            return CaptureContext.ReferenceState.SHARED;
        }
        if (seenValues == null) {
            seenValues = new IdentityHashMap<>();
            visitingValues = new IdentityHashMap<>();
        }
        seenValues.put(source, Boolean.TRUE);
        visitingValues.put(source, Boolean.TRUE);
        return CaptureContext.ReferenceState.FRESH;
    }

    void leaveValue(Object source) {
        visitingValues.remove(source);
    }

    CaptureContext.ReferenceState enterException(Throwable source) {
        if (visitingExceptions != null && visitingExceptions.containsKey(source)) {
            return CaptureContext.ReferenceState.CYCLE;
        }
        if (seenExceptions != null && seenExceptions.containsKey(source)) {
            return CaptureContext.ReferenceState.SHARED;
        }
        if (seenExceptions == null) {
            seenExceptions = new IdentityHashMap<>();
            visitingExceptions = new IdentityHashMap<>();
        }
        seenExceptions.put(source, Boolean.TRUE);
        visitingExceptions.put(source, Boolean.TRUE);
        return CaptureContext.ReferenceState.FRESH;
    }

    void leaveException(Throwable source) {
        visitingExceptions.remove(source);
    }
}
