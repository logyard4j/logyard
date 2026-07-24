package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.zsumz.logyard.api.reload.ReloadResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Lifecycle façade for a parent-directory configuration watch. */
public final class ConfigurationWatcher implements AutoCloseable {
    private static final int MAX_THREAD_COMPONENT_LENGTH = 48;

    private final Path source;
    private final Duration closeTimeout;
    private final ConfigurationWatchLoop watchLoop;
    private final Thread worker;
    private final AtomicBoolean activated = new AtomicBoolean();

    private ConfigurationWatcher(
            ConfigurationWatchRegistration registration,
            Duration debounce,
            Duration closeTimeout,
            Supplier<ReloadResult> reload,
            ReloadDiagnostics diagnostics) {
        source = registration.source();
        this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
        watchLoop = new ConfigurationWatchLoop(
                registration,
                Objects.requireNonNull(debounce, "debounce"),
                Objects.requireNonNull(reload, "reload"),
                Objects.requireNonNull(diagnostics, "diagnostics"));
        worker = new Thread(watchLoop, "logyard-config-watch-" + safeThreadSegment(registration.filename().toString()));
        worker.setDaemon(true);
    }

    public static ConfigurationWatcher start(
            Path source,
            Duration debounce,
            Duration closeTimeout,
            ReloadCoordinator coordinator,
            ReloadDiagnostics diagnostics) {
        Objects.requireNonNull(coordinator, "coordinator");
        return start(source, debounce, closeTimeout, coordinator::reloadIfChanged, diagnostics);
    }

    static ConfigurationWatcher start(
            Path source,
            Duration debounce,
            Duration closeTimeout,
            Supplier<ReloadResult> reload,
            ReloadDiagnostics diagnostics) {
        ConfigurationWatcher watcher = prepare(source, debounce, closeTimeout, reload, diagnostics);
        watcher.activate();
        return watcher;
    }

    public static ConfigurationWatcher prepare(
            Path source,
            Duration debounce,
            Duration closeTimeout,
            Supplier<ReloadResult> reload,
            ReloadDiagnostics diagnostics) {
        ConfigurationWatcher watcher;
        try {
            watcher = new ConfigurationWatcher(
                    ConfigurationWatchRegistration.open(source),
                    debounce,
                    closeTimeout,
                    reload,
                    diagnostics);
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to watch Logyard configuration " + source, failure);
        }
        return watcher;
    }

    public void activate() {
        if (!activated.compareAndSet(false, true)) {
            throw new IllegalStateException("configuration watcher is already active");
        }
        try {
            worker.start();
        } catch (RuntimeException | Error failure) {
            try {
                watchLoop.stop();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        RuntimeException stopFailure = null;
        boolean stopping;
        try {
            stopping = watchLoop.stop();
        } catch (RuntimeException failure) {
            stopping = true;
            stopFailure = failure;
        }
        if (!stopping) {
            return;
        }
        worker.interrupt();
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
            throw new IllegalStateException("configuration watcher did not stop within " + closeTimeout + " for " + source);
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
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_THREAD_COMPONENT_LENGTH));
        for (int index = 0; index < value.length() && result.length() < MAX_THREAD_COMPONENT_LENGTH; index++) {
            char character = value.charAt(index);
            result.append(Character.isLetterOrDigit(character) || character == '-' || character == '_' ? character : '_');
        }
        return result.toString();
    }
}
