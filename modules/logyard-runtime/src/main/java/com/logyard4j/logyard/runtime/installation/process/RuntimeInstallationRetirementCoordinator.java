package com.logyard4j.logyard.runtime.installation.process;

import com.logyard4j.logyard.runtime.installation.GlobalRuntimeAccess;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Owns retirement transaction callbacks, resource closure, and state-completion observation. */
final class RuntimeInstallationRetirementCoordinator {
    private final RuntimeInstallationState state;
    private final GlobalRuntimeAccess globalRuntime;
    private final RuntimeInstallationRetirements retirements = new RuntimeInstallationRetirements();

    RuntimeInstallationRetirementCoordinator(RuntimeInstallationState state, GlobalRuntimeAccess globalRuntime) {
        this.state = state;
        this.globalRuntime = globalRuntime;
    }

    void retire(RuntimeRetirementPlan retirement) {
        retire(retirement, null, null);
    }

    CompletionStage<Void> retire(
            RuntimeRetirementPlan retirement,
            Runnable shutdownBoundary,
            Consumer<Throwable> completion) {
        if (!retirement.required()) {
            if (shutdownBoundary != null) {
                shutdownBoundary.run();
            }
            return CompletableFuture.completedFuture(null);
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
        retirements.observe(finalRetirement, failure -> {
            state.completeRetirement(transaction);
            transaction.completeFinalRetirement(failure);
        });
        transaction.completeShutdownBoundary();
        return transaction.finalRetirement();
    }
}
