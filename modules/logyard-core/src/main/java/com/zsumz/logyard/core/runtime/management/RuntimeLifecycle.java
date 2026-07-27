package com.zsumz.logyard.core.runtime.management;

import com.zsumz.logyard.api.diagnostics.RuntimeHealth;
import com.zsumz.logyard.core.runtime.publication.RuntimePublication;
import com.zsumz.logyard.core.runtime.retirement.RuntimeRetirements;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Coordinates lease-aware runtime observation, flushing, and final plan retirement. */
public final class RuntimeLifecycle {
    private final RuntimeRetirements retirements;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);
    private final CompletableFuture<Void> retirementCompletion = new CompletableFuture<>();

    public RuntimeLifecycle(RuntimeRetirements retirements) {
        this.retirements = Objects.requireNonNull(retirements, "retirements");
    }

    public boolean closed() {
        return phase.get() != Phase.RUNNING;
    }

    public void requireOpen() {
        if (closed()) {
            throw new IllegalStateException("runtime is closed");
        }
    }

    public RuntimeHealth health(Supplier<RuntimeGeneration> currentGeneration, IntSupplier loggerCount) {
        RuntimeGenerationLease lease = acquire(currentGeneration);
        if (lease == null) {
            return RuntimeHealthReporter.stopped(loggerCount.getAsInt());
        }
        try (lease) {
            RuntimeGeneration snapshot = lease.generation();
            return RuntimeHealthReporter.running(
                    loggerCount.getAsInt(), retirements.pendingCount(), snapshot.plan(), snapshot.epoch());
        }
    }

    public void flush(Supplier<RuntimeGeneration> currentGeneration) {
        RuntimeGenerationLease lease = acquire(currentGeneration);
        if (lease == null) {
            return;
        }
        try (lease) {
            retirements.flush(lease.generation().plan());
        }
    }

    /** Starts shutdown while the runtime owner holds its state lock. */
    public boolean beginClose() {
        return phase.compareAndSet(Phase.RUNNING, Phase.CLOSING);
    }

    /** Retires the final plan after the runtime owner has made new operations ineligible. */
    public void finishClose(RuntimeGeneration current) {
        try {
            retirements.finishPlan(current.plan(), current.epoch()).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    retirementCompletion.complete(null);
                } else {
                    retirementCompletion.completeExceptionally(failure);
                }
                phase.set(Phase.CLOSED);
            });
        } catch (RuntimeException | Error failure) {
            retirementCompletion.completeExceptionally(failure);
            phase.set(Phase.CLOSED);
            throw failure;
        }
        retirements.await(current.plan().shutdownTimeout());
    }

    public CompletionStage<Void> retirementCompletion() {
        return retirementCompletion;
    }

    private RuntimeGenerationLease acquire(Supplier<RuntimeGeneration> currentGeneration) {
        return RuntimeGenerationLease.acquire(
                Objects.requireNonNull(currentGeneration, "currentGeneration"),
                this::closed);
    }

    private enum Phase {
        RUNNING,
        CLOSING,
        CLOSED
    }
}
