package com.zsumz.logyard.runtime.installation.process;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.installation.RuntimeInstallation;

import java.util.function.Supplier;

/** Locked lifecycle state and transition decisions; resource work always executes outside this monitor. */
final class RuntimeInstallationState {
    /*
     * Eagerly resolve the shutdown plan and its retirement-plan dependency while the installation
     * class loader is open. Test engines may close isolated URL class loaders before JVM hooks run.
     */
    private static final RuntimeShutdownPlan ALLOWED_SHUTDOWN = RuntimeShutdownPlan.allow();

    private final RuntimeLeaseCounts leaseCounts = new RuntimeLeaseCounts();

    private InstallationPhase phase = InstallationPhase.EMPTY;
    private RuntimeInstallation installation;
    private RuntimeStartTransaction pendingStart;
    private RuntimeRetirementTransaction pendingRetirement;
    private volatile RuntimeInstallation publishedInstallation;
    private RuntimeOwner configurationAuthority;
    private long generation;
    private boolean shutdownHookInstalled;
    private boolean terminating;

    boolean isActive(RuntimeInstallation candidate) {
        return publishedInstallation == candidate;
    }

    synchronized int leaseCount(RuntimeOwner owner) {
        return leaseCounts.get(owner);
    }

    synchronized boolean startCancellationPending() {
        return pendingStart != null && pendingStart.cancelled();
    }

    synchronized AcquisitionPlan reserve(RuntimeOwner owner, Supplier<LogyardRuntime> globalRuntime) {
        LogyardRuntime observedGlobal = globalRuntime.get();
        if (phase == InstallationPhase.STARTING || phase == InstallationPhase.RECONFIGURING
                || phase == InstallationPhase.CLOSING || phase == InstallationPhase.TERMINATED) {
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
        return AcquisitionPlan.start(pendingStart, !shutdownHookInstalled);
    }

    private AcquisitionPlan reserveActive(RuntimeOwner owner, LogyardRuntime observedGlobal) {
        if (observedGlobal != installation.runtime()) {
            phase = InstallationPhase.CLOSING;
            publishedInstallation = null;
            leaseCounts.clear();
            configurationAuthority = null;
            return AcquisitionPlan.closeStale(beginRetirement(installation));
        }
        if (owner.canReplace(configurationAuthority)) {
            phase = InstallationPhase.RECONFIGURING;
            leaseCounts.increment(owner);
            return AcquisitionPlan.reconfigure(installation, ++generation);
        }
        leaseCounts.increment(owner);
        return AcquisitionPlan.shared(installation);
    }

    synchronized RuntimeRetirementPlan release(RuntimeOwner owner, RuntimeInstallation candidate) {
        if (installation != candidate || phase == InstallationPhase.CLOSING || phase == InstallationPhase.TERMINATED) {
            return RuntimeRetirementPlan.none();
        }
        leaseCounts.decrement(owner);
        if (leaseCounts.total() != 0) {
            return RuntimeRetirementPlan.none();
        }
        phase = InstallationPhase.CLOSING;
        publishedInstallation = null;
        configurationAuthority = null;
        return beginRetirement(candidate);
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
        installation = candidate;
        pendingStart = null;
        configurationAuthority = owner;
        leaseCounts.increment(owner);
        phase = InstallationPhase.ACTIVE;
        publishedInstallation = candidate;
    }

    synchronized RuntimeRetirementPlan abortStart(RuntimeStartTransaction transaction, RuntimeInstallation candidate) {
        if (pendingStart != transaction) {
            return RuntimeRetirementPlan.none();
        }
        if (candidate == null) {
            pendingStart = null;
            installation = null;
            phase = terminating ? InstallationPhase.TERMINATED : InstallationPhase.EMPTY;
            return RuntimeRetirementPlan.none();
        }
        installation = candidate;
        publishedInstallation = null;
        leaseCounts.clear();
        configurationAuthority = null;
        phase = InstallationPhase.CLOSING;
        return beginRetirement(candidate);
    }

    synchronized void completeStartRetirement(RuntimeStartTransaction transaction) {
        if (pendingStart == transaction) {
            pendingStart = null;
        }
    }

    synchronized void shutdownHookInstalled() {
        shutdownHookInstalled = true;
    }

    synchronized void commitReconfiguration(RuntimeOwner owner, AcquisitionPlan plan) {
        if (phase != InstallationPhase.RECONFIGURING || generation != plan.generation()) {
            throw InstallationTransitionFailures.forPhase(phase);
        }
        configurationAuthority = owner;
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
        phase = InstallationPhase.CLOSING;
        publishedInstallation = null;
        configurationAuthority = null;
        return beginRetirement(plan.installation());
    }

    synchronized RuntimeShutdownPlan shutdown(RuntimeInstallation expected, boolean terminateProcess) {
        if (terminateProcess) {
            terminating = true;
        }
        if (phase == InstallationPhase.STARTING) {
            if (expected != null && (pendingStart == null || pendingStart.candidate() != expected)) {
                return RuntimeShutdownPlan.rejected();
            }
            pendingStart.cancel();
            return RuntimeShutdownPlan.cancel(pendingStart);
        }
        if (expected != null && installation != expected) {
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
            phase = terminating ? InstallationPhase.TERMINATED : InstallationPhase.EMPTY;
            return terminateProcess ? ALLOWED_SHUTDOWN : RuntimeShutdownPlan.rejected();
        }
        phase = InstallationPhase.CLOSING;
        publishedInstallation = null;
        leaseCounts.clear();
        configurationAuthority = null;
        return RuntimeShutdownPlan.retire(beginRetirement(installation));
    }

    synchronized void completeRetirement(RuntimeRetirementTransaction retirement) {
        if (phase == InstallationPhase.CLOSING
                && pendingRetirement == retirement
                && generation == retirement.generation()
                && installation == retirement.installation()) {
            pendingRetirement = null;
            installation = null;
            phase = terminating ? InstallationPhase.TERMINATED : InstallationPhase.EMPTY;
        }
    }

    private RuntimeRetirementPlan beginRetirement(RuntimeInstallation candidate) {
        pendingRetirement = new RuntimeRetirementTransaction(candidate, ++generation);
        return RuntimeRetirementPlan.close(pendingRetirement);
    }
}
