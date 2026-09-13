package com.logyard4j.logyard.runtime.reload.watcher;

import com.logyard4j.logyard.api.failure.FailureIsolation;
import com.logyard4j.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.logyard4j.logyard.runtime.reload.WatcherReloadOutcome;
import com.logyard4j.logyard.core.failure.ComponentInvocationBoundary;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

final class ConfigurationWatchLoop implements Runnable {
    private static final long POLL_MILLIS = 100L;
    private static final long RECONCILIATION_NANOS = Duration.ofSeconds(1L).toNanos();
    private static final long REGISTRATION_RETRY_NANOS = Duration.ofSeconds(1L).toNanos();

    private final ConfigurationWatchRegistration registration;
    private final Path source;
    private final Path filename;
    private final WatchService watchService;
    private final ReloadDebouncer debouncer;
    private final Supplier<WatcherReloadOutcome> reload;
    private final WatcherDiagnosticBoundary diagnostics;
    private final AtomicReference<WatcherPhase> phase = new AtomicReference<>(WatcherPhase.RUNNING);
    private RegistrationPhase registrationPhase = RegistrationPhase.REGISTERED;
    private long nextReconciliation;
    private long nextRegistrationAttempt;

    ConfigurationWatchLoop(
            ConfigurationWatchRegistration registration,
            Duration debounce,
            Supplier<WatcherReloadOutcome> reload,
            ReloadDiagnostics diagnostics) {
        this.registration = registration;
        source = registration.source();
        filename = registration.filename();
        watchService = registration.watchService();
        debouncer = new ReloadDebouncer(debounce);
        this.reload = reload;
        this.diagnostics = new WatcherDiagnosticBoundary(diagnostics);
        nextReconciliation = saturatedDeadline(System.nanoTime(), RECONCILIATION_NANOS);
    }

    @Override
    public void run() {
        Throwable terminalFailure = null;
        try {
            while (running()) {
                WatchKey key = watchService.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (key != null && consume(key)) {
                    debouncer.signalChange();
                }
                reconcile();
                ComponentInvocationBoundary.invoke(
                        "configuration reload callback",
                        () -> debouncer.runIfDue(reload),
                        (component, failure) -> diagnostics.rejected(source, failure));
            }
        } catch (ClosedWatchServiceException ignored) {
            // Expected during close.
        } catch (InterruptedException interrupted) {
            if (running()) {
                terminalFailure = interrupted;
                Thread.currentThread().interrupt();
            }
        } catch (Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            terminalFailure = failure;
        } finally {
            terminalFailure = closeAfterRun(terminalFailure);
            if (terminalFailure != null && running()) {
                diagnostics.watcherStopped(source, terminalFailure);
            }
        }
    }

    boolean stop() {
        if (!phase.compareAndSet(WatcherPhase.RUNNING, WatcherPhase.STOPPED)) {
            return false;
        }
        try {
            watchService.close();
            return true;
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to close Logyard configuration watcher", failure);
        }
    }

    void markDirty() {
        debouncer.signalChange();
    }

    private boolean consume(WatchKey key) {
        boolean relevant = false;
        boolean symbolicLink = registration.sourceIsSymbolicLink();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW
                    || filename.equals(event.context())
                    || symbolicLink) {
                relevant = true;
            }
        }
        if (!key.reset()) {
            registrationPhase = RegistrationPhase.RETRY_PENDING;
            nextRegistrationAttempt = System.nanoTime();
            relevant = true;
        }
        return relevant;
    }

    private void reconcile() {
        long now = System.nanoTime();
        if (now - nextReconciliation >= 0L) {
            debouncer.signalReconciliation();
            nextReconciliation = saturatedDeadline(now, RECONCILIATION_NANOS);
        }
        if (registrationPhase == RegistrationPhase.RETRY_PENDING && now - nextRegistrationAttempt >= 0L) {
            try {
                registration.reregister();
                registrationPhase = RegistrationPhase.REGISTERED;
                debouncer.signalChange();
            } catch (IOException | RuntimeException failure) {
                diagnostics.rejected(source, failure);
                nextRegistrationAttempt = saturatedDeadline(now, REGISTRATION_RETRY_NANOS);
            }
        }
    }

    private static long saturatedDeadline(long now, long delay) {
        long candidate = now + delay;
        return candidate < 0L && now > 0L ? Long.MAX_VALUE : candidate;
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

    private boolean running() {
        return phase.get() == WatcherPhase.RUNNING;
    }

    private enum WatcherPhase {
        RUNNING,
        STOPPED
    }

    private enum RegistrationPhase {
        REGISTERED,
        RETRY_PENDING
    }
}
