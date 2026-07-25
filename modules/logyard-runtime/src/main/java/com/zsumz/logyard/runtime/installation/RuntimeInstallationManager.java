package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.reload.ReloadResult;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Process-wide coordinator for runtime identity, configuration handoff, and ownership. */
public final class RuntimeInstallationManager {
    private static final RuntimeInstallationManager PROCESS =
            new RuntimeInstallationManager(new LogyardGlobalRuntimeAccess(), new InstallationShutdownHook(), System::getenv);

    private final GlobalRuntimeAccess globalRuntime;
    private final RuntimeShutdownHookRegistrar shutdownHooks;
    private final Supplier<Map<String, String>> environment;
    private final RuntimeInstallationFactory installations;
    private final RuntimeInstallationRetirements retirements = new RuntimeInstallationRetirements();
    private final RuntimeInstallationState state = new RuntimeInstallationState();

    RuntimeInstallationManager(GlobalRuntimeAccess globalRuntime, RuntimeShutdownHookRegistrar shutdownHooks, Supplier<Map<String, String>> environment) {
        this(globalRuntime, shutdownHooks, environment, ManagedRuntimeInstallation::open);
    }

    RuntimeInstallationManager(
            GlobalRuntimeAccess globalRuntime,
            RuntimeShutdownHookRegistrar shutdownHooks,
            Supplier<Map<String, String>> environment,
            RuntimeInstallationFactory installations) {
        this.globalRuntime = Objects.requireNonNull(globalRuntime, "globalRuntime");
        this.shutdownHooks = Objects.requireNonNull(shutdownHooks, "shutdownHooks");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.installations = Objects.requireNonNull(installations, "installations");
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
        retire(state.release(owner, candidate));
    }

    private RuntimeInstallationLease acquire(RuntimeOwner owner, ConfigurationInstallationRequest request) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(request, "request");
        AcquisitionPlan plan = state.reserve(owner, globalRuntime::current);
        return switch (plan.action()) {
            case BORROW -> RuntimeInstallationLease.borrowed(globalRuntime, plan.borrowedRuntime());
            case SHARE -> managedLease(owner, plan.installation());
            case START -> start(owner, request, plan);
            case RECONFIGURE -> reconfigure(owner, request, plan);
            case CLOSE_STALE -> {
                retire(plan.retirement());
                throw InstallationTransitionFailures.forPhase(InstallationPhase.CLOSING);
            }
        };
    }

    private RuntimeInstallationLease start(RuntimeOwner owner, ConfigurationInstallationRequest request, AcquisitionPlan plan) {
        RuntimeInstallation candidate = null;
        RuntimeStartTransaction transaction = Objects.requireNonNull(plan.startTransaction(), "start transaction");
        try {
            candidate = installations.open(request, Map.copyOf(environment.get()));
            state.registerStartCandidate(transaction, candidate);
            RuntimeInstallation starting = candidate;
            globalRuntime.install(candidate.runtime(), () -> shutdownManaged(starting));
            state.requireActiveStart(transaction);
            installShutdownHook(plan);
            state.commitStart(transaction, candidate, owner);
            transaction.completeWithoutRetirement();
            return managedLease(owner, candidate);
        } catch (RuntimeException | Error failure) {
            abortStart(transaction, candidate, failure);
            throw failure;
        }
    }

    private void installShutdownHook(AcquisitionPlan plan) {
        if (plan.installShutdownHook() && shutdownHooks.install(this::shutdownAtExit)) {
            state.shutdownHookInstalled();
        }
    }

    private void abortStart(RuntimeStartTransaction transaction, RuntimeInstallation candidate, Throwable primaryFailure) {
        RuntimeRetirementPlan retirement = state.abortStart(transaction, candidate);
        if (!retirement.required()) {
            transaction.completeWithoutRetirement();
            return;
        }
        retire(retirement, transaction::completeShutdownBoundary, cleanupFailure -> {
            if (cleanupFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
            state.completeStartRetirement(transaction);
            transaction.completeFinalRetirement();
        });
    }

    private RuntimeInstallationLease reconfigure(
            RuntimeOwner owner,
            ConfigurationInstallationRequest request,
            AcquisitionPlan plan) {
        try {
            ReloadResult result = Objects.requireNonNull(plan.installation().reconfigure(request), "runtime reconfiguration result");
            if (result == ReloadResult.REJECTED) {
                throw InstallationTransitionFailures.rejectedHandoff();
            }
            state.commitReconfiguration(owner, plan);
            return managedLease(owner, plan.installation());
        } catch (RuntimeException | Error failure) {
            retire(state.rollbackReconfiguration(owner, plan));
            throw failure;
        }
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
        retire(shutdown.retirement());
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

    private void retire(RuntimeRetirementPlan retirement) {
        retire(retirement, null, null);
    }

    private CompletionStage<Void> retire(
            RuntimeRetirementPlan retirement,
            Runnable shutdownBoundary,
            Consumer<Throwable> completion) {
        if (!retirement.required()) {
            if (shutdownBoundary != null) {
                shutdownBoundary.run();
            }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        RuntimeRetirementTransaction transaction = retirement.transaction();
        if (shutdownBoundary != null) {
            transaction.onShutdownBoundary(shutdownBoundary);
        }
        if (completion != null) {
            transaction.onFinalRetirement(completion);
        }
        if (!transaction.claim()) {
            return transaction.finalRetirement();
        }

        CompletionStage<Void> finalRetirement = retirements.close(transaction.installation(), globalRuntime);
        transaction.completeShutdownBoundary();
        retirements.observe(finalRetirement, failure -> {
            state.completeRetirement(transaction);
            transaction.completeFinalRetirement(failure);
        });
        return finalRetirement;
    }
}
