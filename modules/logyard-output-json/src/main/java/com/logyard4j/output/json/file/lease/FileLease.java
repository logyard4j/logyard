package com.logyard4j.output.json.file.lease;

import com.logyard4j.api.lifecycle.CloseLifecycle;

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

/** Process-wide exclusive ownership of one active output path. */
public final class FileLease implements AutoCloseable {
    private final Path activePath;
    private final Path lockPath;
    private final FileChannel channel;
    private final FileLock lock;
    private final ActiveFileIdentityRegistry.Registration identity;
    private final CloseLifecycle lifecycle = new CloseLifecycle();

    private FileLease(
            Path activePath,
            Path lockPath,
            FileChannel channel,
            FileLock lock,
            ActiveFileIdentityRegistry.Registration identity) {
        this.activePath = activePath;
        this.lockPath = lockPath;
        this.channel = channel;
        this.lock = lock;
        this.identity = identity;
    }

    public static FileLease acquire(Path activePath) {
        Path normalized = Objects.requireNonNull(activePath, "activePath").toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        ActiveFileIdentityRegistry.Registration identity = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            rejectSymbolicLink(normalized, "active output");
            identity = ActiveFileIdentityRegistry.claim(normalized);
            Path lockPath = normalized.resolveSibling(normalized.getFileName() + ".logyard.lock");
            rejectSymbolicLink(lockPath, "output lock");
            FileChannel channel = FileChannel.open(
                    lockPath,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new FileLeaseUnavailableException(normalized, false, null);
                }
                return new FileLease(normalized, lockPath, channel, lock, identity);
            } catch (OverlappingFileLockException overlap) {
                FileLeaseUnavailableException unavailable =
                        new FileLeaseUnavailableException(normalized, true, overlap);
                closeChannel(channel, unavailable);
                throw unavailable;
            } catch (IOException failure) {
                closeChannel(channel, failure);
                throw failure;
            } catch (RuntimeException | Error failure) {
                closeChannel(channel, failure);
                throw failure;
            }
        } catch (IOException failure) {
            closeIdentity(identity);
            throw new UncheckedIOException("failed to acquire Logyard output lock for " + normalized, failure);
        } catch (RuntimeException | Error failure) {
            closeIdentity(identity);
            throw failure;
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
        if (!lifecycle.beginClose()) {
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
        identity.close();
        if (failure != null) {
            throw new UncheckedIOException("failed to release Logyard output lock " + lockPath, failure);
        }
    }

    private static void rejectSymbolicLink(Path path, String kind) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException(kind + " must not be a symbolic link: " + path);
        }
    }

    private static void closeChannel(FileChannel channel, Throwable failure) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeIdentity(ActiveFileIdentityRegistry.Registration identity) {
        if (identity != null) {
            identity.close();
        }
    }
}
