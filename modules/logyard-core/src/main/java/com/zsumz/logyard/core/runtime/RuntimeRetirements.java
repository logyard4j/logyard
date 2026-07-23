package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.core.routing.PlanEpoch;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Coordinates bounded scheduling, observation, and shutdown waiting for retired runtime plans. */
final class RuntimeRetirements {
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> pending = new ConcurrentLinkedQueue<>();
    private final RetirementExecutor executor;
    private final RuntimeRetirementDiagnostics diagnostics;

    RuntimeRetirements() {
        this(new RetirementExecutor(), new StderrRuntimeRetirementDiagnostics());
    }

    RuntimeRetirements(RetirementExecutor executor, RuntimeRetirementDiagnostics diagnostics) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    int pendingCount() {
        return pending.size();
    }

    void replacePlan(RuntimePlan previousPlan, PlanEpoch previousEpoch, RuntimePlan nextPlan, Runnable activation) {
        Objects.requireNonNull(previousPlan, "previousPlan");
        Objects.requireNonNull(previousEpoch, "previousEpoch");
        Objects.requireNonNull(nextPlan, "nextPlan");
        Objects.requireNonNull(activation, "activation");
        if (!executor.reserveReload()) {
            throw new IllegalStateException("Logyard has " + RetirementExecutor.MAX_PENDING_RELOADS
                    + " pending plan retirements; wait for output closure before reloading again");
        }

        boolean retirementScheduled = false;
        try {
            activation.run();
            observe(previousEpoch.retire(() -> RuntimeOutputs.closeNotReused(previousPlan, nextPlan), executor::scheduleReload));
            retirementScheduled = true;
        } finally {
            if (!retirementScheduled) {
                executor.cancelReloadReservation();
            }
        }
    }

    void finishPlan(RuntimePlan plan, PlanEpoch epoch) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(epoch, "epoch");
        observe(epoch.retire(() -> RuntimeOutputs.closeAll(plan), executor::scheduleFinal));
    }

    void await(Duration timeout) {
        long timeoutNanos = saturatedNanos(Objects.requireNonNull(timeout, "timeout"));
        long startedAt = System.nanoTime();
        for (CompletableFuture<Void> retirement : pending) {
            long remaining = timeoutNanos - (System.nanoTime() - startedAt);
            if (remaining <= 0) {
                diagnostics.shutdownDeadlineElapsed();
                return;
            }
            try {
                retirement.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeoutFailure) {
                diagnostics.shutdownDeadlineElapsed();
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException alreadyReported) {
                // Completion observation reports the original retirement failure once.
            }
        }
    }

    private void observe(CompletableFuture<Void> retirement) {
        CompletableFuture<Void> observation = new CompletableFuture<>();
        pending.add(observation);
        retirement.whenComplete((ignored, failure) -> {
            try {
                if (failure != null) {
                    diagnostics.retirementFailed(failure);
                }
            } finally {
                pending.remove(observation);
                if (failure == null) {
                    observation.complete(null);
                } else {
                    observation.completeExceptionally(failure);
                }
            }
        });
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
