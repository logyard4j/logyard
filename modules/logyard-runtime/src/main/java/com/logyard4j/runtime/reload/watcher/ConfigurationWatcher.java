package com.logyard4j.runtime.reload.watcher;

import com.logyard4j.runtime.diagnostics.ReloadDiagnostics;
import com.logyard4j.runtime.reload.WatcherReloadOutcome;
import com.logyard4j.runtime.reload.coordination.ReloadCoordinator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Lifecycle façade for a parent-directory configuration watch. */
public final class ConfigurationWatcher implements AutoCloseable {
    private enum Lifecycle {
        PREPARED,
        RUNNING,
        STOPPING,
        STOPPED
    }

    private final Path source;
    private final Duration closeTimeout;
    private final ConfigurationWatchLoop watchLoop;
    private final ConfigurationWatchWorker worker;
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.PREPARED);

    private ConfigurationWatcher(
            ConfigurationWatchRegistration registration,
            Duration debounce,
            Duration closeTimeout,
            Supplier<WatcherReloadOutcome> reload,
            ReloadDiagnostics diagnostics) {
        registration.claim();
        source = registration.source();
        this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
        watchLoop = new ConfigurationWatchLoop(
                registration,
                Objects.requireNonNull(debounce, "debounce"),
                Objects.requireNonNull(reload, "reload"),
                Objects.requireNonNull(diagnostics, "diagnostics"));
        worker = new ConfigurationWatchWorker(
                source,
                () -> {
                    try {
                        watchLoop.run();
                    } finally {
                        lifecycle.set(Lifecycle.STOPPED);
                    }
                });
    }

    public static ConfigurationWatcher start(
            Path source,
            Duration debounce,
            Duration closeTimeout,
            ReloadCoordinator coordinator,
            ReloadDiagnostics diagnostics) {
        Objects.requireNonNull(coordinator, "coordinator");
        return start(source, debounce, closeTimeout, coordinator::reloadForWatcher, diagnostics);
    }

    static ConfigurationWatcher start(
            Path source,
            Duration debounce,
            Duration closeTimeout,
            Supplier<WatcherReloadOutcome> reload,
            ReloadDiagnostics diagnostics) {
        ConfigurationWatcher watcher = prepare(source, debounce, closeTimeout, reload, diagnostics);
        watcher.activate();
        return watcher;
    }

    public static ConfigurationWatcher prepare(
            Path source,
            Duration debounce,
            Duration closeTimeout,
            Supplier<WatcherReloadOutcome> reload,
            ReloadDiagnostics diagnostics) {
        try {
            return prepare(ConfigurationWatchRegistration.open(source), debounce, closeTimeout, reload, diagnostics);
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to watch Logyard configuration " + source, failure);
        }
    }

    public static ConfigurationWatcher prepare(
            ConfigurationWatchRegistration registration,
            Duration debounce,
            Duration closeTimeout,
            Supplier<WatcherReloadOutcome> reload,
            ReloadDiagnostics diagnostics) {
        Objects.requireNonNull(registration, "registration");
        try {
            return new ConfigurationWatcher(registration, debounce, closeTimeout, reload, diagnostics);
        } catch (RuntimeException | Error failure) {
            try {
                registration.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public void activate() {
        if (!lifecycle.compareAndSet(Lifecycle.PREPARED, Lifecycle.RUNNING)) {
            throw new IllegalStateException("configuration watcher is already active");
        }
        try {
            worker.start();
        } catch (RuntimeException | Error failure) {
            lifecycle.set(Lifecycle.STOPPING);
            try {
                watchLoop.stop();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            lifecycle.set(Lifecycle.STOPPED);
            throw failure;
        }
    }

    /** Marks a change observed by the registration handshake before the worker starts. */
    public void markDirtyBeforeActivation() {
        if (lifecycle.get() != Lifecycle.PREPARED) {
            throw new IllegalStateException("configuration watcher is already active");
        }
        watchLoop.markDirty();
    }

    public boolean isRunning() {
        return lifecycle.get() == Lifecycle.RUNNING && worker.isAlive();
    }

    @Override
    public void close() {
        RuntimeException stopFailure = null;
        Lifecycle previous = lifecycle.getAndUpdate(current -> current == Lifecycle.STOPPED ? current : Lifecycle.STOPPING);
        if (previous == Lifecycle.STOPPED) {
            return;
        }
        if (previous != Lifecycle.STOPPING) {
            try {
                watchLoop.stop();
            } catch (RuntimeException failure) {
                stopFailure = failure;
            }
        }
        if (worker.isAlive()) {
            worker.interrupt();
        }
        try {
            awaitWorker();
        } catch (RuntimeException joinFailure) {
            if (stopFailure == null) {
                throw joinFailure;
            }
            stopFailure.addSuppressed(joinFailure);
        }
        if (stopFailure != null) {
            throw stopFailure;
        }
        if (!worker.isAlive()) {
            lifecycle.set(Lifecycle.STOPPED);
        }
    }

    private void awaitWorker() {
        worker.await(closeTimeout, source);
    }
}
