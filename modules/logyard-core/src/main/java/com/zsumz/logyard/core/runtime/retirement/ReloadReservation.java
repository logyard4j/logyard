package com.zsumz.logyard.core.runtime.retirement;

import com.zsumz.logyard.core.runtime.RuntimeReloadDeferredException;

import java.util.Objects;

/** A reload capacity slot whose release moves to the retirement task after scheduling succeeds. */
final class ReloadReservation implements AutoCloseable {
    private final RetirementExecutor executor;
    private Phase phase = Phase.HELD;

    private ReloadReservation(RetirementExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    static ReloadReservation acquire(RetirementExecutor executor) {
        if (!executor.reserveReload()) {
            throw new RuntimeReloadDeferredException("Logyard has " + RetirementExecutor.MAX_PENDING_RELOADS
                    + " pending plan retirements; wait for output closure before reloading again");
        }
        return new ReloadReservation(executor);
    }

    void transferToRetirementTask() {
        if (phase != Phase.HELD) {
            throw new IllegalStateException("Logyard reload reservation is not held");
        }
        phase = Phase.TRANSFERRED;
    }

    @Override
    public void close() {
        if (phase == Phase.HELD) {
            phase = Phase.RELEASED;
            executor.cancelReloadReservation();
        }
    }

    private enum Phase {
        HELD,
        TRANSFERRED,
        RELEASED
    }
}
