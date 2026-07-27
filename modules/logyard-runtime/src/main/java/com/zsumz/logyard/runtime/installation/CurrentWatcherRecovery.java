package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.runtime.reload.WatcherReloadOutcome;

import java.util.Objects;
import java.util.function.Supplier;

import static com.zsumz.logyard.runtime.installation.RuntimeInstallationTransitions.Phase.RECONFIGURING;

/** Restarts the former watcher only when a replacement attempt has already begun to retire it. */
final class CurrentWatcherRecovery {
    private final RuntimeInstallationTransitions transitions;
    private final Supplier<WatcherReloadOutcome> watcherReload;
    private Phase phase = Phase.ACTIVE;

    CurrentWatcherRecovery(RuntimeInstallationTransitions transitions, Supplier<WatcherReloadOutcome> watcherReload) {
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.watcherReload = Objects.requireNonNull(watcherReload, "watcherReload");
    }

    void retire(ActiveRuntimeConfiguration current) {
        phase = Phase.RETIREMENT_ATTEMPTED;
        current.closeWatcher();
    }

    void restartAfterFailure(ActiveRuntimeConfiguration current, Throwable primaryFailure) {
        if (phase != Phase.RETIREMENT_ATTEMPTED) {
            return;
        }
        phase = Phase.RECOVERY_ATTEMPTED;
        try {
            ActiveRuntimeConfiguration restarted = current.restartWatcher(watcherReload);
            if (!transitions.replaceDuring(RECONFIGURING, current, restarted)) {
                restarted.closeWatcher();
            }
        } catch (RuntimeException restartFailure) {
            primaryFailure.addSuppressed(restartFailure);
        }
    }

    private enum Phase {
        ACTIVE,
        RETIREMENT_ATTEMPTED,
        RECOVERY_ATTEMPTED
    }
}
