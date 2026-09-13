package com.logyard4j.logyard.core.runtime.retirement;

import com.logyard4j.logyard.core.diagnostics.EmergencyText;
import com.logyard4j.logyard.core.diagnostics.EmergencyReporter;
import com.logyard4j.logyard.core.failure.ComponentInvocationBoundary;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One bounded daemon per runtime for output retirement.
 *
 * <p>Reload is an operator path, so it is rejected before plan replacement when too many
 * older plans are still waiting to retire. Publisher threads only enqueue work into a slot
 * reserved by the reload operation; they never block while releasing an epoch lease.</p>
 */
final class RetirementExecutor {
    static final int MAX_PENDING_RELOADS = 63;
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private final ArrayBlockingQueue<Runnable> queue =
            new ArrayBlockingQueue<>(MAX_PENDING_RELOADS + 1);
    private final Semaphore reloadPermits = new Semaphore(MAX_PENDING_RELOADS);
    private final AtomicInteger pendingReloads = new AtomicInteger();
    private final AtomicReference<WorkerPhase> workerPhase = new AtomicReference<>(WorkerPhase.NOT_STARTED);
    private final Thread worker = new Thread(
            this::runLoop,
            "logyard-plan-retirement-" + NEXT_ID.incrementAndGet());

    RetirementExecutor() {
        worker.setDaemon(true);
    }

    boolean reserveReload() {
        if (!reloadPermits.tryAcquire()) {
            return false;
        }
        pendingReloads.incrementAndGet();
        return true;
    }

    void cancelReloadReservation() {
        int remaining = pendingReloads.decrementAndGet();
        if (remaining < 0) {
            pendingReloads.incrementAndGet();
            throw new IllegalStateException("Logyard retirement reservation underflow");
        }
        reloadPermits.release();
    }

    void scheduleReload(Runnable task) {
        Objects.requireNonNull(task, "task");
        enqueue(() -> {
            try {
                task.run();
            } finally {
                pendingReloads.decrementAndGet();
                reloadPermits.release();
            }
        }, true);
    }

    void scheduleFinal(Runnable task) {
        Objects.requireNonNull(task, "task");
        enqueue(() -> {
            try {
                task.run();
            } finally {
                workerPhase.set(WorkerPhase.FINAL_RETIREMENT_COMPLETED);
            }
        }, false);
    }

    int pendingReloads() {
        return pendingReloads.get();
    }

    private void enqueue(Runnable task, boolean releasesReloadReservation) {
        if (!queue.offer(task)) {
            if (releasesReloadReservation) {
                pendingReloads.decrementAndGet();
                reloadPermits.release();
            }
            throw new IllegalStateException("Logyard retirement queue capacity invariant was violated");
        }
        if (workerPhase.compareAndSet(WorkerPhase.NOT_STARTED, WorkerPhase.RUNNING)) {
            worker.start();
        }
    }

    private void runLoop() {
        while (!shouldStop()) {
            try {
                Runnable task = queue.poll(100, TimeUnit.MILLISECONDS);
                if (task != null) {
                    task.run();
                }
            } catch (InterruptedException ignored) {
                // Wake-up signal for newly queued work or a completed final retirement.
            } catch (Throwable failure) {
                ComponentInvocationBoundary.report(
                        "runtime retirement worker",
                        failure,
                        (component, current) -> EmergencyReporter.STDERR.report(
                                "Logyard retirement worker failure: "
                                        + EmergencyText.failureSummary(current, 4_096)));
            }
        }
    }

    private boolean shouldStop() {
        return workerPhase.get() == WorkerPhase.FINAL_RETIREMENT_COMPLETED
                && pendingReloads.get() == 0
                && queue.isEmpty();
    }

    private enum WorkerPhase {
        NOT_STARTED,
        RUNNING,
        FINAL_RETIREMENT_COMPLETED
    }
}
