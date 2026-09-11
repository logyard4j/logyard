package com.logyard4j.runtime.installation.process;

import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.runtime.installation.RuntimeInstallation;

import java.util.function.Supplier;

/** Locked lifecycle state and transition decisions; resource work always executes outside this monitor. */
final class RuntimeInstallationState {
    /*
     * Eagerly resolve the shutdown plan and its retirement-plan dependency while the installation
     * class loader is open. Test engines may close isolated URL class loaders before JVM hooks run.
     */
    private static final RuntimeShutdownPlan ALLOWED_SHUTDOWN = RuntimeShutdownPlan.allow();
    private final RuntimeLeaseCounts leaseCounts = new RuntimeLeaseCounts();
    private final ManagedInstallationState managed = new ManagedInstallationState();
    private final ProcessShutdownState processShutdown = new ProcessShutdownState();
    private InstallationPhase phase = InstallationPhase.EMPTY;
    private RuntimeStartTransaction pendingStart;
    private RuntimeRetirementTransaction pendingRetirement;
    private long generation;

    boolean isActive(RuntimeInstallation candidate) {
        return managed.isPublishedAs(candidate);
    }

    synchronized int leaseCount(RuntimeOwner owner) {
        return leaseCounts.get(owner);
    }

    synchronized boolean startCancellationPending() {
        return pendingStart != null && pendingStart.cancelled();
    }

    synchronized AcquisitionPlan reserve(RuntimeOwner owner, Supplier<LogyardRuntime> globalRuntime) {
        LogyardRuntime observedGlobal = globalRuntime.get();
        if (acquisitionBlocked()) {
            throw InstallationTransitionFailures.forPhase(phase);
        }
        if (phase == InstallationPhase.ACTIVE) {
            return reserveActive(owner, observedGlobal);
        }
        if (observedGlobal != null) {
            if (owner == RuntimeOwner.ADAPTER) {
                return AcquisitionPlan.borrowed(observedGlobal);
            }
            throw new IllegalStateException("Logyard is already initialized outside the runtime installation manager");
        }
        phase = InstallationPhase.STARTING;
        pendingStart = new RuntimeStartTransaction(++generation);
        return AcquisitionPlan.start(pendingStart, processShutdown.requiresShutdownHook());
    }

    private AcquisitionPlan reserveActive(RuntimeOwner owner, LogyardRuntime observedGlobal) {
        if (!managed.currentRuntimeMatches(observedGlobal)) {
            return AcquisitionPlan.closeStale(retire(managed.installation()));
        }
        if (managed.ownerCanReplaceConfiguration(owner)) {
            phase = InstallationPhase.RECONFIGURING;
            leaseCounts.increment(owner);
            return AcquisitionPlan.reconfigure(managed.installation(), ++generation);
        }
        leaseCounts.increment(owner);
        return AcquisitionPlan.shared(managed.installation());
    }

    synchronized RuntimeRetirementPlan release(RuntimeOwner owner, RuntimeInstallation candidate) {
        if (!managed.isCurrent(candidate) || phase == InstallationPhase.CLOSING || phase == InstallationPhase.TERMINATED) {
            return RuntimeRetirementPlan.none();
        }
        leaseCounts.decrement(owner);
        if (leaseCounts.total() != 0) {
            return RuntimeRetirementPlan.none();
        }
        return retire(candidate);
    }

    synchronized void registerStartCandidate(RuntimeStartTransaction transaction, RuntimeInstallation candidate) {
        transaction.candidate(candidate);
        requireActiveStart(transaction);
    }

    synchronized void requireActiveStart(RuntimeStartTransaction transaction) {
        if (phase != InstallationPhase.STARTING || pendingStart != transaction
                || generation != transaction.generation() || transaction.cancelled()) {
            throw InstallationTransitionFailures.forPhase(phase);
        }
    }

    synchronized void commitStart(RuntimeStartTransaction transaction, RuntimeInstallation candidate, RuntimeOwner owner) {
        requireActiveStart(transaction);
        pendingStart = null;
        activate(candidate, owner);
    }

    private void activate(RuntimeInstallation candidate, RuntimeOwner owner) {
        managed.prepare(candidate, owner);
        leaseCounts.increment(owner);
        phase = InstallationPhase.ACTIVE;
        managed.publish();
    }

    synchronized RuntimeRetirementPlan abortStart(RuntimeStartTransaction transaction, RuntimeInstallation candidate) {
        if (pendingStart != transaction) {
            return RuntimeRetirementPlan.none();
        }
        if (candidate == null) {
            returnToIdleAfterCancelledStart();
            return RuntimeRetirementPlan.none();
        }
        managed.retainForRetirement(candidate);
        return retire(candidate);
    }

    synchronized void completeStartRetirement(RuntimeStartTransaction transaction) {
        if (pendingStart == transaction) {
            pendingStart = null;
        }
    }

    synchronized void shutdownHookInstalled() {
        processShutdown.markShutdownHookInstalled();
    }

    synchronized void commitReconfiguration(RuntimeOwner owner, AcquisitionPlan plan) {
        if (phase != InstallationPhase.RECONFIGURING || generation != plan.generation()) {
            throw InstallationTransitionFailures.forPhase(phase);
        }
        managed.assignConfigurationAuthority(owner);
        phase = InstallationPhase.ACTIVE;
    }

    synchronized RuntimeRetirementPlan rollbackReconfiguration(RuntimeOwner owner, AcquisitionPlan plan) {
        if (phase != InstallationPhase.RECONFIGURING || generation != plan.generation()) {
            return RuntimeRetirementPlan.none();
        }
        leaseCounts.decrement(owner);
        if (leaseCounts.total() != 0) {
            phase = InstallationPhase.ACTIVE;
            return RuntimeRetirementPlan.none();
        }
        return retire(plan.installation());
    }

    synchronized RuntimeShutdownPlan shutdown(RuntimeInstallation expected, boolean terminateProcess) {
        if (terminateProcess) {
            processShutdown.markProcessTerminating();
        }
        if (phase == InstallationPhase.STARTING) {
            if (expected != null && (pendingStart == null || pendingStart.candidate() != expected)) {
                return RuntimeShutdownPlan.rejected();
            }
            pendingStart.cancel();
            return RuntimeShutdownPlan.cancel(pendingStart);
        }
        if (expected != null && !managed.isCurrent(expected)) {
            return RuntimeShutdownPlan.rejected();
        }
        if (phase == InstallationPhase.CLOSING) {
            if (pendingRetirement != null) {
                return RuntimeShutdownPlan.join(pendingRetirement);
            }
            return pendingStart == null ? ALLOWED_SHUTDOWN : RuntimeShutdownPlan.cancel(pendingStart);
        }
        if (phase == InstallationPhase.TERMINATED) {
            return RuntimeShutdownPlan.rejected();
        }
        if (phase == InstallationPhase.EMPTY) {
            phase = processShutdown.terminalOrEmptyPhase();
            return terminateProcess ? ALLOWED_SHUTDOWN : RuntimeShutdownPlan.rejected();
        }
        return RuntimeShutdownPlan.retire(retire(managed.installation()));
    }

    synchronized void completeRetirement(RuntimeRetirementTransaction retirement) {
        if (phase == InstallationPhase.CLOSING
                && pendingRetirement == retirement
                && generation == retirement.generation()
                && managed.isCurrent(retirement.installation())) {
            pendingRetirement = null;
            managed.clearInstallation();
            phase = processShutdown.terminalOrEmptyPhase();
        }
    }

    private boolean acquisitionBlocked() {
        return phase == InstallationPhase.STARTING
                || phase == InstallationPhase.RECONFIGURING
                || phase == InstallationPhase.CLOSING
                || phase == InstallationPhase.TERMINATED;
    }

    private RuntimeRetirementPlan retire(RuntimeInstallation candidate) {
        phase = InstallationPhase.CLOSING;
        clearManagedOwnership();
        return beginRetirement(candidate);
    }

    private void clearManagedOwnership() {
        managed.withdrawPublication();
        leaseCounts.clear();
        managed.clearConfigurationAuthority();
    }

    private void returnToIdleAfterCancelledStart() {
        pendingStart = null;
        managed.clearInstallation();
        phase = processShutdown.terminalOrEmptyPhase();
    }

    private RuntimeRetirementPlan beginRetirement(RuntimeInstallation candidate) {
        pendingRetirement = new RuntimeRetirementTransaction(candidate, ++generation);
        return RuntimeRetirementPlan.close(pendingRetirement);
    }

}
