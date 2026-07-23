package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.core.failure.ComponentFailureCollector;
import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

final class ManagedRuntimeInstallation {
    private final ReentrantLock transition = new ReentrantLock();
    private final DefaultLogyardRuntime runtime;
    private final Map<String, String> environment;
    private ActiveRuntimeConfiguration active;
    private boolean closed;

    private ManagedRuntimeInstallation(DefaultLogyardRuntime runtime, Map<String, String> environment) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.environment = Map.copyOf(environment);
    }

    static ManagedRuntimeInstallation open(
            ConfigurationInstallationRequest request,
            Map<String, String> environment) {
        ConfigurationSnapshot snapshot = read(request);
        LogyardConfig config = snapshot.parse(environment);
        RuntimeAssembly assembly = LogyardRuntimeFactory.assemble(config, null);
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(assembly.plan());
        ManagedRuntimeInstallation installation = new ManagedRuntimeInstallation(runtime, environment);
        try {
            LogyardRuntimeFactory.attach(runtime, assembly);
            ActiveRuntimeConfiguration active = ActiveRuntimeConfiguration.prepare(
                    request,
                    runtime,
                    snapshot,
                    assembly,
                    installation.environment,
                    installation::reloadNow);
            installation.active = active;
            active.activateWatcher();
            return installation;
        } catch (RuntimeException | Error failure) {
            ComponentFailureCollector cleanupFailures = new ComponentFailureCollector();
            ComponentInvocationBoundary.invoke("failed installation runtime close", runtime::close, cleanupFailures);
            cleanupFailures.suppressInto(failure);
            throw failure;
        }
    }

    ReloadResult reconfigure(ConfigurationInstallationRequest request) {
        transition.lock();
        try {
            requireOpen();
            ConfigurationSnapshot snapshot = read(request);
            if (active.sameSourceAndDigest(request, snapshot)) {
                return ReloadResult.UNCHANGED;
            }

            RuntimeAssembly currentAssembly = active.coordinator().currentAssembly();
            RuntimeAssembly candidate = null;
            ActiveRuntimeConfiguration replacement = null;
            boolean currentWatcherClosed = false;
            try {
                LogyardConfig config = snapshot.parse(environment);
                candidate = LogyardRuntimeFactory.assemble(config, currentAssembly);
                replacement = ActiveRuntimeConfiguration.prepare(
                        request,
                        runtime,
                        snapshot,
                        candidate,
                        environment,
                        this::reloadNow);
                replacement.activateWatcher();
                active.closeWatcher();
                currentWatcherClosed = true;
                runtime.reload(candidate.plan());
                LogyardRuntimeFactory.attach(runtime, candidate);
                active = replacement;
                return ReloadResult.APPLIED;
            } catch (RuntimeException | Error failure) {
                closeReplacement(replacement, candidate, currentAssembly, failure);
                if (currentWatcherClosed) {
                    restartCurrentWatcher(failure);
                }
                throw failure;
            }
        } finally {
            transition.unlock();
        }
    }

    ReloadResult reloadNow() {
        boolean interrupted = false;
        try {
            try {
                transition.lockInterruptibly();
            } catch (InterruptedException interruption) {
                interrupted = true;
                return ReloadResult.REJECTED;
            }
            try {
                return closed ? ReloadResult.REJECTED : active.coordinator().reloadIfChanged();
            } finally {
                transition.unlock();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    LogyardRuntime runtime() {
        return runtime;
    }

    boolean watchesConfiguration() {
        transition.lock();
        try {
            return !closed && active.watchesConfiguration();
        } finally {
            transition.unlock();
        }
    }

    void close(GlobalRuntimeAccess globalRuntime) {
        transition.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            ComponentFailureCollector failures = new ComponentFailureCollector();
            ComponentInvocationBoundary.invoke("configuration watcher close", active::closeWatcher, failures);
            ComponentInvocationBoundary.invoke("installed runtime close", () -> {
                if (!globalRuntime.shutdownIfCurrent(runtime)) {
                    runtime.close();
                }
            }, failures);
            failures.throwIfPresent("runtime installation close");
        } finally {
            transition.unlock();
        }
    }

    private void restartCurrentWatcher(Throwable primaryFailure) {
        try {
            active = active.restartWatcher(this::reloadNow);
        } catch (RuntimeException restartFailure) {
            primaryFailure.addSuppressed(restartFailure);
        }
    }

    private static void closeReplacement(
            ActiveRuntimeConfiguration replacement,
            RuntimeAssembly candidate,
            RuntimeAssembly current,
            Throwable failure) {
        if (replacement != null) {
            try {
                replacement.closeWatcher();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (candidate != null) {
            candidate.closeCandidateOutputs(current, failure);
        }
    }

    private static ConfigurationSnapshot read(ConfigurationInstallationRequest request) {
        try {
            return request.snapshot();
        } catch (IOException failure) {
            throw new IllegalStateException("failed to read Logyard configuration " + request.description(), failure);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Logyard runtime installation is closed");
        }
    }
}
