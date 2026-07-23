package com.zsumz.logyard.output.json.file.rotation;

import com.zsumz.logyard.output.json.file.FileLease;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/** One bounded maintenance worker for one rotating output and its exclusive lease. */
public final class ArchiveMaintenance implements AutoCloseable {
    static final int QUEUE_CAPACITY = 32;
    private static final int COPY_BUFFER_BYTES = 64 * 1_024;

    private final ArchiveNaming naming;
    private final RotationPolicy policy;
    private final FileLease lease;
    private final ArrayBlockingQueue<Path> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Thread worker;

    private ArchiveMaintenance(ArchiveNaming naming, RotationPolicy policy, FileLease lease) {
        this.naming = naming;
        this.policy = policy;
        this.lease = lease;
        worker = new Thread(this::runLoop, "logyard-archive-maintenance-" + sanitizeThreadName(
                lease.activePath().getFileName().toString()));
        worker.setDaemon(true);
    }

    public static ArchiveMaintenance start(
            ArchiveNaming naming,
            RotationPolicy policy,
            FileLease lease) {
        ArchiveMaintenance maintenance = new ArchiveMaintenance(
                Objects.requireNonNull(naming, "naming"),
                Objects.requireNonNull(policy, "policy"),
                Objects.requireNonNull(lease, "lease"));
        try {
            maintenance.reconcile();
            maintenance.worker.start();
            return maintenance;
        } catch (RuntimeException | Error startupFailure) {
            try {
                lease.close();
            } catch (RuntimeException closeFailure) {
                startupFailure.addSuppressed(closeFailure);
            }
            throw startupFailure;
        }
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
        return worker.isAlive();
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
        boolean interrupted = false;
        try {
            long millis = saturatedMillis(timeout);
            if (millis > 0) {
                worker.join(millis);
            }
        } catch (InterruptedException interruption) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (worker.isAlive()) {
            throw new IllegalStateException(
                    "archive maintenance did not stop within " + timeout + " for " + lease.activePath());
        }
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
                    archive = queue.poll(100L, java.util.concurrent.TimeUnit.MILLISECONDS);
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
            if (policy.compression() == RotationPolicy.Compression.GZIP && Files.exists(archive)) {
                compress(archive);
            }
            enforceRetention();
        } catch (RuntimeException maintenanceFailure) {
            recordFailure(maintenanceFailure);
        }
    }

    private void reconcile() {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(naming.directory())) {
            for (Path candidate : entries) {
                if (naming.isGzipTemporary(candidate)) {
                    Files.deleteIfExists(candidate);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to reconcile Logyard archive temporaries", failure);
        }

        if (policy.compression() == RotationPolicy.Compression.GZIP) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(naming.directory())) {
                for (Path candidate : entries) {
                    naming.recognize(candidate)
                            .filter(identity -> !identity.compressed())
                            .ifPresent(identity -> compress(identity.path()));
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("failed to reconcile Logyard archives", failure);
            }
        }
        enforceRetention();
    }

    private void compress(Path archive) {
        ArchiveNaming.ArchiveIdentity identity = naming.recognize(archive)
                .orElseThrow(() -> new IllegalArgumentException("not a Logyard archive: " + archive));
        if (identity.compressed()) {
            return;
        }
        if (Files.isSymbolicLink(archive)) {
            throw new IllegalStateException("refusing to compress symbolic-link archive " + archive);
        }
        Path target = naming.gzipPath(archive);
        Path temporary = naming.gzipTemporaryPath(archive);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("compressed Logyard archive already exists: " + target);
        }
        try {
            Files.deleteIfExists(temporary);
            try (FileChannel source = FileChannel.open(
                            archive, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    FileChannel destination = FileChannel.open(
                            temporary,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS));
                    InputStream input = Channels.newInputStream(source);
                    GZIPOutputStream gzip = new GZIPOutputStream(
                            Channels.newOutputStream(destination), COPY_BUFFER_BYTES, true)) {
                byte[] copyBuffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = input.read(copyBuffer)) >= 0) {
                    if (read > 0) {
                        gzip.write(copyBuffer, 0, read);
                    }
                }
                gzip.finish();
                gzip.flush();
                destination.force(true);
            }
            atomicPromote(temporary, target);
            Files.delete(archive);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new UncheckedIOException("failed to gzip Logyard archive " + archive, failure);
        }
    }

    private void enforceRetention() {
        Comparator<ArchiveNaming.ArchiveIdentity> order = ArchiveNaming.chronologicalOrder();
        PriorityQueue<ArchiveNaming.ArchiveIdentity> newest =
                new PriorityQueue<>(policy.retainedArchives(), order);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(naming.directory())) {
            for (Path candidate : entries) {
                var recognized = naming.recognize(candidate);
                if (recognized.isEmpty()) {
                    continue;
                }
                ArchiveNaming.ArchiveIdentity identity = recognized.get();
                if (newest.size() < policy.retainedArchives()) {
                    newest.add(identity);
                    continue;
                }
                ArchiveNaming.ArchiveIdentity oldestKept = newest.peek();
                if (oldestKept != null && order.compare(identity, oldestKept) > 0) {
                    deleteArchive(newest.remove().path());
                    newest.add(identity);
                } else {
                    deleteArchive(identity.path());
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to enforce Logyard archive retention", failure);
        }
    }

    private static void deleteArchive(Path archive) {
        try {
            Files.deleteIfExists(archive);
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to delete expired Logyard archive " + archive, failure);
        }
    }

    private static void atomicPromote(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target);
        }
    }

    private void recordFailure(RuntimeException maintenanceFailure) {
        failure.compareAndSet(null, maintenanceFailure);
    }

    private static long saturatedMillis(Duration timeout) {
        try {
            long millis = timeout.toMillis();
            return timeout.isZero() ? 0L : Math.max(1L, millis);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String sanitizeThreadName(String value) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), 48));
        for (int index = 0; index < value.length() && result.length() < 48; index++) {
            char character = value.charAt(index);
            result.append(Character.isLetterOrDigit(character) || character == '-' || character == '_'
                    ? character
                    : '_');
        }
        return result.toString();
    }
}
