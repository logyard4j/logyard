package com.zsumz.logyard.api.lifecycle;

import java.util.concurrent.atomic.AtomicReference;

/** One-way {@code OPEN -> CLOSED} lifecycle for resources whose close operation is idempotent.
 * @hidden
 */
@com.zsumz.logyard.api.annotation.InternalApi
public final class CloseLifecycle {
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.OPEN);

    /** Creates an open lifecycle. */
    public CloseLifecycle() {
    }

    /**
     * Checks whether closure has not yet been claimed.
     *
     * @return whether the lifecycle is open
     */
    public boolean open() {
        return phase.get() == Phase.OPEN;
    }

    /**
     * Claims the sole close transition.
     *
     * @return whether this caller claimed closure
     */
    public boolean beginClose() {
        return phase.compareAndSet(Phase.OPEN, Phase.CLOSED);
    }

    /**
     * Checks whether closure has been claimed.
     *
     * @return whether a caller has claimed closure, regardless of cleanup progress
     */
    public boolean closed() {
        return phase.get() == Phase.CLOSED;
    }

    private enum Phase {
        OPEN,
        CLOSED
    }
}
