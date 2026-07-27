package com.zsumz.logyard.output.json.file.rotation;

import com.zsumz.logyard.output.json.file.lease.FileLease;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded maintenance worker for one rotating output and its exclusive lease. */
public final class ArchiveMaintenance implements AutoCloseable {
    static final int QUEUE_CAPACITY = 32;

    private final RotationPolicy policy;
    private final FileLease lease;
    private final ArchiveOperations operations;
    private final ArrayBlockingQueue<Path> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final ArchiveQueuePoll queuePoll = new ArchiveQueuePoll(queue, closing);
    private final ArchiveMaintenanceWorker worker;

    private ArchiveMaintenance(ArchiveNaming naming, RotationPolicy policy, FileLease lease) {
        this.policy = policy;
        this.lease = lease;
        operations = new ArchiveOperations(naming, policy);
        worker = new ArchiveMaintenanceWorker(lease.activePath(), this::runLoop);
    }

    /**
     * Reconciles archives and starts the worker.
     *
     * <p>The caller retains lease ownership if this method throws. A successful return transfers
     * ownership to the maintenance worker, which releases the lease when it stops.</p>
     */
    public static ArchiveMaintenance start(
            ArchiveNaming naming,
            RotationPolicy policy,
            FileLease lease) {
        ArchiveMaintenance maintenance = new ArchiveMaintenance(
                Objects.requireNonNull(naming, "naming"),
                Objects.requireNonNull(policy, "policy"),
                Objects.requireNonNull(lease, "lease"));
        maintenance.operations.reconcile();
        maintenance.worker.start();
        return maintenance;
    }

    /** Queues maintenance without blocking the logging thread. Failure becomes sticky. */
    public void submit(Path archive) {
        Objects.requireNonNull(archive, "archive");
        if (closing.get()) {
            recordFailure(new IllegalStateException("archive maintenance is closing for " + lease.activePath()));
            return;
        }
        if (!queue.offer(archive.toAbsolutePath().normalize())) {
            recordFailure(new IllegalStateException(
                    "archive maintenance queue reached its bounded capacity of " + QUEUE_CAPACITY
                            + " for " + lease.activePath()));
            return;
        }
    }

    public void throwIfFailed() {
        RuntimeException recorded = failure.get();
        if (recorded != null) {
            throw new IllegalStateException(
                    "Logyard archive maintenance failed for " + lease.activePath(), recorded);
        }
    }

    /** Fixed queue capacity for operational health snapshots. */
    public int queueCapacity() {
        return QUEUE_CAPACITY;
    }

    /** Bounded queue depth for operational health snapshots. */
    public int queuedTasks() {
        return queue.size();
    }

    /** Whether the maintenance worker is still running. */
    public boolean workerAlive() {
        return worker.alive();
    }

    /** Whether close has begun. */
    public boolean closing() {
        return closing.get();
    }

    /** Class name of the sticky maintenance failure, or {@code null}. */
    public String failureType() {
        RuntimeException recorded = failure.get();
        return recorded == null ? null : recorded.getClass().getName();
    }

    public void close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("maintenance close timeout must not be negative");
        }
        closing.set(true);
        worker.interruptIfPolling(queuePoll);
        if (timeout.isZero()) {
            throwIfFailed();
            return;
        }
        worker.await(timeout, lease.activePath());
        throwIfFailed();
    }

    /**
     * Aborts a writer initialization before any archive can have been submitted.
     *
     * <p>This rollback is not constrained by the runtime shutdown deadline: ownership must be
     * released before failed construction returns.</p>
     */
    public void abortBeforeUse() {
        closing.set(true);
        worker.interruptIfPolling(queuePoll);
        worker.awaitStopped();
        throwIfFailed();
    }

    @Override
    public void close() {
        close(policy.maintenanceShutdownTimeout());
    }

    private void runLoop() {
        try {
            while (!closing.get() || !queue.isEmpty()) {
                Path archive;
                try {
                    archive = queuePoll.next();
                } catch (InterruptedException interrupted) {
                    if (!closing.get()) {
                        Thread.currentThread().interrupt();
                        recordFailure(new IllegalStateException(
                                "archive maintenance worker was interrupted", interrupted));
                        return;
                    }
                    continue;
                }
                if (archive != null) {
                    maintain(archive);
                }
            }
        } finally {
            try {
                lease.close();
            } catch (RuntimeException closeFailure) {
                recordFailure(closeFailure);
            }
        }
    }

    private void maintain(Path archive) {
        try {
            operations.maintain(archive);
        } catch (RuntimeException maintenanceFailure) {
            recordFailure(maintenanceFailure);
        }
    }

    private void recordFailure(RuntimeException maintenanceFailure) {
        failure.compareAndSet(null, maintenanceFailure);
    }

}
