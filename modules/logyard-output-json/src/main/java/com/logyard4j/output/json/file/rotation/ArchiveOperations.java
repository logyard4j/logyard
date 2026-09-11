package com.logyard4j.output.json.file.rotation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/** Filesystem housekeeping for archive reconciliation, compression, and exact retention. */
final class ArchiveOperations {
    private static final int COPY_BUFFER_BYTES = 64 * 1_024;

    private final ArchiveNaming naming;
    private final RotationPolicy policy;

    ArchiveOperations(ArchiveNaming naming, RotationPolicy policy) {
        this.naming = Objects.requireNonNull(naming, "naming");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    void maintain(Path archive) {
        if (policy.compression() == RotationPolicy.Compression.GZIP && Files.exists(archive)) {
            compress(archive);
        }
        enforceRetention();
    }

    void reconcile() {
        removeTemporaryArchives();
        if (policy.compression() == RotationPolicy.Compression.GZIP) {
            compressPendingArchives();
        }
        enforceRetention();
    }

    private void removeTemporaryArchives() {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(naming.directory())) {
            for (Path candidate : entries) {
                if (naming.isGzipTemporary(candidate)) {
                    Files.deleteIfExists(candidate);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to reconcile Logyard archive temporaries", failure);
        }
    }

    private void compressPendingArchives() {
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
            compressToTemporary(archive, temporary);
            atomicPromote(temporary, target);
            Files.delete(archive);
        } catch (IOException failure) {
            deleteTemporaryAfterFailure(temporary, failure);
            throw new UncheckedIOException("failed to gzip Logyard archive " + archive, failure);
        }
    }

    private static void compressToTemporary(Path archive, Path temporary) throws IOException {
        try (FileChannel source = FileChannel.open(archive, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                FileChannel destination = FileChannel.open(
                        temporary,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
                InputStream input = Channels.newInputStream(source);
                GZIPOutputStream gzip = new GZIPOutputStream(Channels.newOutputStream(destination), COPY_BUFFER_BYTES, true)) {
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
    }

    private void enforceRetention() {
        Comparator<ArchiveNaming.ArchiveIdentity> order = ArchiveNaming.chronologicalOrder();
        PriorityQueue<ArchiveNaming.ArchiveIdentity> newest = new PriorityQueue<>(policy.retainedArchives(), order);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(naming.directory())) {
            for (Path candidate : entries) {
                naming.recognize(candidate).ifPresent(identity -> retainNewest(identity, newest, order));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to enforce Logyard archive retention", failure);
        }
    }

    private void retainNewest(
            ArchiveNaming.ArchiveIdentity candidate,
            PriorityQueue<ArchiveNaming.ArchiveIdentity> newest,
            Comparator<ArchiveNaming.ArchiveIdentity> order) {
        if (newest.size() < policy.retainedArchives()) {
            newest.add(candidate);
            return;
        }
        ArchiveNaming.ArchiveIdentity oldestKept = newest.peek();
        if (oldestKept != null && order.compare(candidate, oldestKept) > 0) {
            deleteArchive(newest.remove().path());
            newest.add(candidate);
        } else {
            deleteArchive(candidate.path());
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

    private static void deleteTemporaryAfterFailure(Path temporary, IOException primaryFailure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }
}
