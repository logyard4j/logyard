package com.zsumz.logyard.api.event;

import java.util.function.Supplier;

/** Installs and restores the event-local capture context for one bounded capture operation. */
final class CaptureContextScope {
    private static final ThreadLocal<CaptureContext> CURRENT = new ThreadLocal<>();

    private CaptureContextScope() {
    }

    static CaptureContext currentOrCreate() {
        CaptureContext current = CURRENT.get();
        return current == null ? CaptureContext.create() : current;
    }

    static <T> T within(CaptureContext context, Supplier<T> action) {
        CaptureContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return action.get();
        } finally {
            // Keep the thread-local slot, but release the completed graph and its application objects.
            CURRENT.set(previous);
        }
    }
}
