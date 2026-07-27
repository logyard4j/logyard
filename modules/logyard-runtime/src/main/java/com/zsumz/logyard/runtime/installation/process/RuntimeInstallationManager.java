package com.zsumz.logyard.runtime.installation.process;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.installation.ConfigurationInstallationRequest;
import com.zsumz.logyard.runtime.installation.GlobalRuntimeAccess;
import com.zsumz.logyard.runtime.installation.ManagedRuntimeInstallation;
import com.zsumz.logyard.runtime.installation.RuntimeInstallation;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Process-wide coordinator for runtime identity, configuration handoff, and ownership. */
public final class RuntimeInstallationManager {
    private static final RuntimeInstallationManager PROCESS =
            new RuntimeInstallationManager(new LogyardGlobalRuntimeAccess(), new InstallationShutdownHook(), System::getenv);

    private final GlobalRuntimeAccess globalRuntime;
    private final RuntimeInstallationState state = new RuntimeInstallationState();
    private final RuntimeInstallationRetirementCoordinator retirements;
    private final RuntimeInstallationStarter starter;
    private final InstallationAcquisitionReconfiguration reconfiguration;

    public RuntimeInstallationManager(
            GlobalRuntimeAccess globalRuntime,
            RuntimeShutdownHookRegistrar shutdownHooks,
            Supplier<Map<String, String>> environment) {
        this(globalRuntime, shutdownHooks, environment, ManagedRuntimeInstallation::open);
    }

    RuntimeInstallationManager(
            GlobalRuntimeAccess globalRuntime,
            RuntimeShutdownHookRegistrar shutdownHooks,
            Supplier<Map<String, String>> environment,
            RuntimeInstallationFactory installations) {
        this.globalRuntime = Objects.requireNonNull(globalRuntime, "globalRuntime");
        retirements = new RuntimeInstallationRetirementCoordinator(state, this.globalRuntime);
        starter = new RuntimeInstallationStarter(
                state,
                this.globalRuntime,
                Objects.requireNonNull(shutdownHooks, "shutdownHooks"),
                Objects.requireNonNull(environment, "environment"),
                Objects.requireNonNull(installations, "installations"),
                retirements,
                this::shutdownAtExit,
                this::shutdownManaged);
        reconfiguration = new InstallationAcquisitionReconfiguration(state, retirements);
    }

    public static RuntimeInstallationManager process() {
        return PROCESS;
    }

    public RuntimeInstallationLease acquireApplication(ConfigurationInstallationRequest request) {
        return acquire(RuntimeOwner.APPLICATION, request);
    }

    public RuntimeInstallationLease acquireFramework(ConfigurationInstallationRequest request) {
        return acquire(RuntimeOwner.FRAMEWORK, request);
    }

    public RuntimeInstallationLease acquireAdapter(ConfigurationInstallationRequest request) {
        return acquire(RuntimeOwner.ADAPTER, request);
    }

    boolean isActive(RuntimeInstallation candidate) {
        return state.isActive(candidate);
    }

    int leaseCount(RuntimeOwner owner) {
        return state.leaseCount(owner);
    }

    boolean startCancellationPending() {
        return state.startCancellationPending();
    }

    void release(RuntimeOwner owner, RuntimeInstallation candidate) {
        retirements.retire(state.release(owner, candidate));
    }

    private RuntimeInstallationLease acquire(RuntimeOwner owner, ConfigurationInstallationRequest request) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(request, "request");
        AcquisitionPlan plan = state.reserve(owner, globalRuntime::current);
        return switch (plan.action()) {
            case BORROW -> RuntimeInstallationLease.borrowed(globalRuntime, plan.borrowedRuntime());
            case SHARE -> managedLease(owner, plan.installation());
            case START -> managedLease(owner, starter.start(owner, request, plan));
            case RECONFIGURE -> managedLease(owner, reconfiguration.reconfigure(owner, request, plan));
            case CLOSE_STALE -> {
                retirements.retire(plan.retirement());
                throw InstallationTransitionFailures.forPhase(InstallationPhase.CLOSING);
            }
        };
    }

    private RuntimeInstallationLease managedLease(RuntimeOwner owner, RuntimeInstallation installation) {
        return RuntimeInstallationLease.managed(this, globalRuntime, owner, installation);
    }

    private void shutdownAtExit() {
        requestShutdown(null, true);
    }

    private boolean shutdownManaged(RuntimeInstallation candidate) {
        return requestShutdown(candidate, false);
    }

    private boolean requestShutdown(RuntimeInstallation expected, boolean terminateProcess) {
        RuntimeShutdownPlan shutdown = state.shutdown(expected, terminateProcess);
        retirements.retire(shutdown.retirement());
        RuntimeRetirementTransaction closing = shutdown.retirement().transaction();
        if (closing != null && !closing.awaitShutdownBoundary()) {
            globalRuntime.detachIfCurrent(closing.installation().runtime());
        }
        RuntimeStartTransaction cancelledStart = shutdown.cancelledStart();
        if (cancelledStart != null) {
            RuntimeInstallation candidate = cancelledStart.candidate();
            if (candidate != null) {
                globalRuntime.detachIfCurrent(candidate.runtime());
            }
            cancelledStart.awaitShutdownBoundary();
        }
        return shutdown.accepted();
    }
}
