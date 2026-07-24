package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.api.failure.FailureIsolation;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class ConfigurationWatchLoop implements Runnable {
    private static final long POLL_MILLIS = 100L;

    private final Path source;
    private final Path filename;
    private final WatchService watchService;
    private final ReloadDebouncer debouncer;
    private final Supplier<ReloadResult> reload;
    private final ReloadDiagnosticBoundary diagnostics;
    private final AtomicBoolean stopped = new AtomicBoolean();

    ConfigurationWatchLoop(
            ConfigurationWatchRegistration registration,
            Duration debounce,
            Supplier<ReloadResult> reload,
            ReloadDiagnostics diagnostics) {
        source = registration.source();
        filename = registration.filename();
        watchService = registration.watchService();
        debouncer = new ReloadDebouncer(debounce);
        this.reload = reload;
        this.diagnostics = new ReloadDiagnosticBoundary(diagnostics);
    }

    @Override
    public void run() {
        Throwable terminalFailure = null;
        try {
            while (!stopped.get()) {
                WatchKey key = watchService.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (key != null && consume(key)) {
                    debouncer.signalChange();
                }
                ComponentInvocationBoundary.invoke(
                        "configuration reload callback",
                        () -> debouncer.runIfDue(reload),
                        (component, failure) -> diagnostics.rejected(source, failure));
            }
        } catch (ClosedWatchServiceException ignored) {
            // Expected during close.
        } catch (InterruptedException interrupted) {
            if (!stopped.get()) {
                terminalFailure = interrupted;
                Thread.currentThread().interrupt();
            }
        } catch (Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            terminalFailure = failure;
        } finally {
            terminalFailure = closeAfterRun(terminalFailure);
            if (terminalFailure != null && !stopped.get()) {
                diagnostics.watcherStopped(source, terminalFailure);
            }
        }
    }

    boolean stop() {
        if (!stopped.compareAndSet(false, true)) {
            return false;
        }
        try {
            watchService.close();
            return true;
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to close Logyard configuration watcher", failure);
        }
    }

    private boolean consume(WatchKey key) {
        boolean relevant = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW || filename.equals(event.context())) {
                relevant = true;
            }
        }
        if (!key.reset()) {
            throw new IllegalStateException("configuration watch key is no longer valid");
        }
        return relevant;
    }

    private Throwable closeAfterRun(Throwable terminalFailure) {
        try {
            watchService.close();
            return terminalFailure;
        } catch (IOException closeFailure) {
            if (terminalFailure == null) {
                return closeFailure;
            }
            terminalFailure.addSuppressed(closeFailure);
            return terminalFailure;
        }
    }
}
