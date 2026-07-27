package com.zsumz.logyard.output.json.file.rotation;

import java.util.concurrent.atomic.AtomicReference;

/** Defines submission, draining, and terminal states for one archive-maintenance worker. */
final class ArchiveMaintenanceState {
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);

    boolean acceptingSubmissions() {
        return phase.get() == Phase.RUNNING;
    }

    boolean closing() {
        return phase.get() != Phase.RUNNING;
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
            return current == Phase.DRAINING ? DrainRequest.IN_PROGRESS : DrainRequest.COMPLETE;
        }
    }

    void complete() {
        phase.set(Phase.CLOSED);
    }

    enum Phase {
        RUNNING,
        DRAINING,
        CLOSED
    }

    enum DrainRequest {
        STARTED,
        IN_PROGRESS,
        COMPLETE
    }
}
