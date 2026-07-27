package com.zsumz.logyard.core.runtime.retirement;

import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean finalRetirementCompleted = new AtomicBoolean();
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
                finalRetirementCompleted.set(true);
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
        if (started.compareAndSet(false, true)) {
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
                        (component, current) -> System.err.println(
                                "Logyard retirement worker failure: "
                                        + EmergencyText.failureSummary(current, 4_096)));
            }
        }
    }

    private boolean shouldStop() {
        return finalRetirementCompleted.get()
                && pendingReloads.get() == 0
                && queue.isEmpty();
    }
}
