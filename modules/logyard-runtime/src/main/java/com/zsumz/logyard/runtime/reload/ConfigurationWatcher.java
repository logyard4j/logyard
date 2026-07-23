package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Parent-directory watcher that supports editor writes and atomic file replacement. */
public final class ConfigurationWatcher implements AutoCloseable {
    private static final long POLL_MILLIS = 100L;

    private final Path source;
    private final Path filename;
    private final long debounceNanos;
    private final Duration closeTimeout;
    private final ReloadCoordinator coordinator;
    private final ReloadDiagnostics diagnostics;
    private final WatchService watchService;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread worker;

    private ConfigurationWatcher(
            Path source,
            Duration debounce,
            Duration closeTimeout,
            ReloadCoordinator coordinator,
            ReloadDiagnostics diagnostics) throws IOException {
        this.source = source.toAbsolutePath().normalize();
        filename = this.source.getFileName();
        if (filename == null) {
            throw new IOException("configuration path has no filename: " + source);
        }
        debounceNanos = saturatedNanos(Objects.requireNonNull(debounce, "debounce"));
        this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        Path parent = this.source.getParent();
        if (parent == null) {
            throw new IOException("configuration path has no parent directory: " + source);
        }
        watchService = FileSystems.getDefault().newWatchService();
        try {
            parent.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException | RuntimeException registrationFailure) {
            try {
                watchService.close();
            } catch (IOException closeFailure) {
                registrationFailure.addSuppressed(closeFailure);
            }
            throw registrationFailure;
        }
        worker = new Thread(this::runLoop, "logyard-config-watch-" + safeThreadSegment(filename.toString()));
        worker.setDaemon(true);
    }

    public static ConfigurationWatcher start(
            Path source,
            Duration debounce,
            Duration closeTimeout,
            ReloadCoordinator coordinator,
            ReloadDiagnostics diagnostics) {
        try {
            ConfigurationWatcher watcher = new ConfigurationWatcher(
                    source, debounce, closeTimeout, coordinator, diagnostics);
            watcher.worker.start();
            return watcher;
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to watch Logyard configuration " + source, failure);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            watchService.close();
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to close Logyard configuration watcher", failure);
        } finally {
            worker.interrupt();
            awaitWorker();
        }
    }

    private void runLoop() {
        long reloadDeadline = Long.MAX_VALUE;
        Throwable terminalFailure = null;
        try {
            while (!closed.get()) {
                WatchKey key = watchService.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (key != null) {
                    boolean relevant = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            relevant = true;
                            continue;
                        }
                        Object context = event.context();
                        if (filename.equals(context)) {
                            relevant = true;
                        }
                    }
                    if (!key.reset()) {
                        throw new IllegalStateException("configuration watch key is no longer valid");
                    }
                    if (relevant) {
                        reloadDeadline = deadlineAfter(debounceNanos);
                    }
                }
                if (reloadDeadline != Long.MAX_VALUE && deadlineReached(reloadDeadline)) {
                    coordinator.reloadIfChanged();
                    reloadDeadline = Long.MAX_VALUE;
                }
            }
        } catch (ClosedWatchServiceException ignored) {
            // Expected during close.
        } catch (InterruptedException interrupted) {
            if (!closed.get()) {
                terminalFailure = interrupted;
                Thread.currentThread().interrupt();
            }
        } catch (RuntimeException failure) {
            terminalFailure = failure;
        } finally {
            try {
                watchService.close();
            } catch (IOException closeFailure) {
                if (terminalFailure == null) {
                    terminalFailure = closeFailure;
                } else {
                    terminalFailure.addSuppressed(closeFailure);
                }
            }
            if (terminalFailure != null && !closed.get()) {
                diagnostics.watcherStopped(source, terminalFailure);
            }
        }
    }

    private void awaitWorker() {
        boolean interrupted = false;
        try {
            long millis = saturatedMillis(closeTimeout);
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
                    "configuration watcher did not stop within " + closeTimeout + " for " + source);
        }
    }

    private static long deadlineAfter(long delayNanos) {
        long now = System.nanoTime();
        long deadline = now + delayNanos;
        return deadline < 0 && now > 0 ? Long.MAX_VALUE - 1 : deadline;
    }

    private static boolean deadlineReached(long deadline) {
        return System.nanoTime() - deadline >= 0;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedMillis(Duration duration) {
        try {
            long millis = duration.toMillis();
            return duration.isZero() ? 0L : Math.max(1L, millis);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String safeThreadSegment(String value) {
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
