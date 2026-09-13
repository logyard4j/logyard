package com.logyard4j.runtime.installation.process;

import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.api.reload.ReloadResult;
import com.logyard4j.runtime.installation.ConfigurationInstallationRequest;
import com.logyard4j.runtime.installation.GlobalRuntimeAccess;
import com.logyard4j.runtime.installation.RuntimeInstallation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeRetirementBoundaryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedRetirementClearsTheInstallationBeforePublishingTheShutdownBoundary(boolean failed) {
        CompletableFuture<Void> completion = failed
                ? CompletableFuture.failedFuture(new IllegalStateException("close failed"))
                : CompletableFuture.completedFuture(null);
        RuntimeInstallationState state = new RuntimeInstallationState();
        RuntimeRetirementPlan plan = retirement(state, completion);
        AtomicBoolean finalObserved = new AtomicBoolean();
        AtomicBoolean readyAtBoundary = new AtomicBoolean();
        var coordinator = new RuntimeInstallationRetirementCoordinator(state, new LogyardGlobalRuntimeAccess());

        CompletionStage<Void> result = coordinator.retire(plan, () -> {
            state.reserve(RuntimeOwner.APPLICATION, () -> null);
            readyAtBoundary.set(finalObserved.get());
        }, failure -> finalObserved.set(true));

        assertTrue(readyAtBoundary.get());
        assertSame(plan.transaction().finalRetirement(), result);
        assertEquals(failed, result.toCompletableFuture().isCompletedExceptionally());
        assertTrue(plan.transaction().awaitShutdownBoundary());
    }

    @Test
    void unfinishedRetirementPublishesTheBoundedBoundaryAndKeepsAcquisitionClosed() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        RuntimeInstallationState state = new RuntimeInstallationState();
        RuntimeRetirementPlan plan = retirement(state, completion);
        AtomicBoolean boundaryObserved = new AtomicBoolean();
        var coordinator = new RuntimeInstallationRetirementCoordinator(state, new LogyardGlobalRuntimeAccess());

        CompletionStage<Void> result = coordinator.retire(plan, () -> boundaryObserved.set(true), null);

        assertTrue(boundaryObserved.get());
        assertFalse(result.toCompletableFuture().isDone());
        assertThrows(IllegalStateException.class, () -> state.reserve(RuntimeOwner.APPLICATION, () -> null));
        completion.complete(null);
        assertTrue(result.toCompletableFuture().isDone());
        state.reserve(RuntimeOwner.APPLICATION, () -> null);
        assertSame(plan.transaction().finalRetirement(), result);
    }

    private static RuntimeRetirementPlan retirement(
            RuntimeInstallationState state, CompletableFuture<Void> completion) {
        RuntimeInstallation installation = new StubInstallation(completion);
        AcquisitionPlan start = state.reserve(RuntimeOwner.APPLICATION, () -> null);
        state.commitStart(start.startTransaction(), installation, RuntimeOwner.APPLICATION);
        return state.release(RuntimeOwner.APPLICATION, installation);
    }

    private record StubInstallation(CompletableFuture<Void> completion) implements RuntimeInstallation {
        @Override
        public LogyardRuntime runtime() {
            throw new UnsupportedOperationException("this retirement test does not publish a global runtime");
        }

        @Override
        public ReloadResult reconfigure(ConfigurationInstallationRequest request) {
            return ReloadResult.UNCHANGED;
        }

        @Override
        public ReloadResult reloadNow() {
            return ReloadResult.UNCHANGED;
        }

        @Override
        public boolean watchesConfiguration() {
            return false;
        }

        @Override
        public CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime) {
            return completion;
        }
    }
}
