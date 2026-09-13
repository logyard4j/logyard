package com.logyard4j.logyard.core.runtime.retirement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeRetirementAwaitTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedLifecycleDoesNotReportADeadlineWhenNoWaitIsAllowed(boolean failed) {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        RuntimeRetirements retirements = new RuntimeRetirements(new RetirementExecutor(), diagnostics);
        CompletableFuture<Void> completion = failed
                ? CompletableFuture.failedFuture(new IllegalStateException("already reported"))
                : CompletableFuture.completedFuture(null);

        retirements.await(completion, Duration.ZERO);

        assertEquals(0, diagnostics.deadlines);
        assertEquals(failed, completion.isCompletedExceptionally());
    }

    @Test
    void elapsedDeadlineLeavesLifecycleCompletionPending() {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        RuntimeRetirements retirements = new RuntimeRetirements(new RetirementExecutor(), diagnostics);
        CompletableFuture<Void> completion = new CompletableFuture<>();

        retirements.await(completion, Duration.ZERO);

        assertEquals(1, diagnostics.deadlines);
        assertFalse(completion.isDone());
    }

    @Test
    void interruptedWaitPreservesTheInterruptAndPendingCompletion() {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        RuntimeRetirements retirements = new RuntimeRetirements(new RetirementExecutor(), diagnostics);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Thread.currentThread().interrupt();
        try {
            retirements.await(completion, Duration.ofSeconds(1));

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, diagnostics.deadlines);
            assertFalse(completion.isDone());
        } finally {
            Thread.interrupted();
        }
    }

    private static final class RecordingDiagnostics implements RuntimeRetirementDiagnostics {
        private int deadlines;

        @Override
        public void retirementFailed(Throwable failure) {
            throw new AssertionError("await must not repeat retirement diagnostics", failure);
        }

        @Override
        public void shutdownDeadlineElapsed() {
            deadlines++;
        }
    }
}
