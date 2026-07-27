package com.zsumz.logyard.api.lifecycle;

import java.util.concurrent.atomic.AtomicReference;

/** One-way {@code OPEN -> CLOSED} lifecycle for resources whose close operation is idempotent. */
public final class CloseLifecycle {
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.OPEN);

    public boolean open() {
        return phase.get() == Phase.OPEN;
    }

    /** Claims the sole close transition. */
    public boolean beginClose() {
        return phase.compareAndSet(Phase.OPEN, Phase.CLOSED);
    }

    public boolean closed() {
        return phase.get() == Phase.CLOSED;
    }

    private enum Phase {
        OPEN,
        CLOSED
    }
}
