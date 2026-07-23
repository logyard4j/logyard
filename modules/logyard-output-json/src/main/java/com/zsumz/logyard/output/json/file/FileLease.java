package com.zsumz.logyard.output.json.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide exclusive ownership of one active output path. */
public final class FileLease implements AutoCloseable {
    private final Path activePath;
    private final Path lockPath;
    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private FileLease(Path activePath, Path lockPath, FileChannel channel, FileLock lock) {
        this.activePath = activePath;
        this.lockPath = lockPath;
        this.channel = channel;
        this.lock = lock;
    }

    public static FileLease acquire(Path activePath) {
        Path normalized = Objects.requireNonNull(activePath, "activePath").toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            rejectSymbolicLink(normalized, "active output");
            Path lockPath = normalized.resolveSibling(normalized.getFileName() + ".logyard.lock");
            rejectSymbolicLink(lockPath, "output lock");
            FileChannel channel = FileChannel.open(
                    lockPath,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    throw new IllegalStateException("Logyard output is already owned: " + normalized);
                }
                return new FileLease(normalized, lockPath, channel, lock);
            } catch (OverlappingFileLockException overlap) {
                channel.close();
                throw new IllegalStateException("Logyard output is already owned in this JVM: " + normalized, overlap);
            } catch (RuntimeException | Error failure) {
                channel.close();
                throw failure;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to acquire Logyard output lock for " + normalized, failure);
        }
    }

    public Path activePath() {
        return activePath;
    }

    public Path lockPath() {
        return lockPath;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException failure = null;
        try {
            lock.release();
        } catch (IOException releaseFailure) {
            failure = releaseFailure;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw new UncheckedIOException("failed to release Logyard output lock " + lockPath, failure);
        }
    }

    private static void rejectSymbolicLink(Path path, String kind) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException(kind + " must not be a symbolic link: " + path);
        }
    }
}
