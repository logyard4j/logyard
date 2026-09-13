package com.logyard4j.logyard.runtime.installation.process;

import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.runtime.installation.ConfigurationInstallationRequest;
import com.logyard4j.logyard.runtime.installation.GlobalRuntimeAccess;
import com.logyard4j.logyard.runtime.installation.RuntimeInstallation;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns candidate construction, global publication, shutdown-hook installation, and start rollback. */
final class RuntimeInstallationStarter {
    private final RuntimeInstallationState state;
    private final GlobalRuntimeAccess globalRuntime;
    private final RuntimeShutdownHookRegistrar shutdownHooks;
    private final Supplier<Map<String, String>> environment;
    private final RuntimeInstallationFactory installations;
    private final RuntimeInstallationRetirementCoordinator retirements;
    private final Runnable shutdownAtExit;
    private final Function<RuntimeInstallation, Boolean> managedShutdown;

    RuntimeInstallationStarter(
            RuntimeInstallationState state,
            GlobalRuntimeAccess globalRuntime,
            RuntimeShutdownHookRegistrar shutdownHooks,
            Supplier<Map<String, String>> environment,
            RuntimeInstallationFactory installations,
            RuntimeInstallationRetirementCoordinator retirements,
            Runnable shutdownAtExit,
            Function<RuntimeInstallation, Boolean> managedShutdown) {
        this.state = Objects.requireNonNull(state, "state");
        this.globalRuntime = Objects.requireNonNull(globalRuntime, "globalRuntime");
        this.shutdownHooks = Objects.requireNonNull(shutdownHooks, "shutdownHooks");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.installations = Objects.requireNonNull(installations, "installations");
        this.retirements = Objects.requireNonNull(retirements, "retirements");
        this.shutdownAtExit = Objects.requireNonNull(shutdownAtExit, "shutdownAtExit");
        this.managedShutdown = Objects.requireNonNull(managedShutdown, "managedShutdown");
    }

    RuntimeInstallation start(RuntimeOwner owner, ConfigurationInstallationRequest request, AcquisitionPlan plan) {
        RuntimeInstallation candidate = null;
        RuntimeStartTransaction transaction = Objects.requireNonNull(plan.startTransaction(), "start transaction");
        try {
            candidate = installations.open(request, Map.copyOf(environment.get()));
            state.registerStartCandidate(transaction, candidate);
            RuntimeInstallation starting = candidate;
            LogyardRuntime candidateRuntime = candidate.runtime();
            if (!transaction.publish(() -> globalRuntime.install(candidateRuntime, () -> managedShutdown.apply(starting)))) {
                state.requireActiveStart(transaction);
            }
            state.requireActiveStart(transaction);
            installShutdownHook(plan);
            state.commitStart(transaction, candidate, owner);
            transaction.completeWithoutRetirement();
            return candidate;
        } catch (RuntimeException | Error failure) {
            abortStart(transaction, candidate, failure);
            throw failure;
        }
    }

    private void installShutdownHook(AcquisitionPlan plan) {
        if (plan.installShutdownHook() && shutdownHooks.install(shutdownAtExit)) {
            state.shutdownHookInstalled();
        }
    }

    private void abortStart(RuntimeStartTransaction transaction, RuntimeInstallation candidate, Throwable primaryFailure) {
        RuntimeRetirementPlan retirement = state.abortStart(transaction, candidate);
        if (!retirement.required()) {
            transaction.completeWithoutRetirement();
            return;
        }
        retirements.retire(retirement, transaction::completeShutdownBoundary, cleanupFailure -> {
            if (cleanupFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
            state.completeStartRetirement(transaction);
            transaction.completeFinalRetirement();
        });
    }
}
