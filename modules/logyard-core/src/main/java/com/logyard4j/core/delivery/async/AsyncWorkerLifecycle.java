package com.logyard4j.core.delivery.async;

import java.util.concurrent.atomic.AtomicReference;

/** Defines the legal lifecycle transitions for one asynchronous delivery worker. */
final class AsyncWorkerLifecycle {
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);

    boolean acceptingEvents() {
        return phase.get() == Phase.RUNNING;
    }

    DrainRequest requestDrain() {
        while (true) {
            Phase current = phase.get();
            if (current == Phase.RUNNING) {
                if (phase.compareAndSet(Phase.RUNNING, Phase.DRAINING)) {
                    return DrainRequest.STARTED;
                }
                continue;
            }
            if (current == Phase.DRAINING) {
                return DrainRequest.IN_PROGRESS;
            }
            return DrainRequest.COMPLETE;
        }
    }

    boolean beginDelegateClose() {
        return phase.compareAndSet(Phase.DRAINING, Phase.CLOSING_DELEGATE);
    }

    void completeDelegateClose() {
        phase.set(Phase.CLOSED);
    }

    boolean workerRunning() {
        return phase.get() == Phase.RUNNING;
    }

    boolean delegateCloseStarted() {
        Phase current = phase.get();
        return current == Phase.CLOSING_DELEGATE || current == Phase.CLOSED;
    }

    enum Phase {
        RUNNING,
        DRAINING,
        CLOSING_DELEGATE,
        CLOSED
    }

    enum DrainRequest {
        STARTED,
        IN_PROGRESS,
        COMPLETE
    }
}
