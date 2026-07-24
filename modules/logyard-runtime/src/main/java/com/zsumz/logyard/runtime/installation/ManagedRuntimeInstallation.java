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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.ReentrantLock;

final class ManagedRuntimeInstallation implements RuntimeInstallation {
    private enum State {
        OPEN,
        RECONFIGURING,
        RELOADING,
        CLOSED
    }

    private final ReentrantLock transition = new ReentrantLock();
    private final DefaultLogyardRuntime runtime;
    private final Map<String, String> environment;
    private ActiveRuntimeConfiguration active;
    private State state = State.OPEN;

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

    @Override
    public ReloadResult reconfigure(ConfigurationInstallationRequest request) {
        ActiveRuntimeConfiguration current = begin(State.RECONFIGURING);
        if (current == null) {
            return ReloadResult.REJECTED;
        }

        RuntimeAssembly currentAssembly = current.coordinator().currentAssembly();
        RuntimeAssembly candidate = null;
        ActiveRuntimeConfiguration replacement = null;
        boolean currentWatcherClosed = false;
        try {
            ConfigurationSnapshot snapshot = read(request);
            if (current.sameSourceAndDigest(request, snapshot)) {
                return finishUnchanged(State.RECONFIGURING);
            }
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
            current.closeWatcher();
            currentWatcherClosed = true;
            return commitReplacement(current, replacement, candidate, currentAssembly);
        } catch (RuntimeException | Error failure) {
            closeReplacement(replacement, candidate, currentAssembly, failure);
            if (currentWatcherClosed) {
                restartCurrentWatcher(current, failure);
            }
            finishFailed(State.RECONFIGURING);
            throw failure;
        }
    }

    @Override
    public ReloadResult reloadNow() {
        ActiveRuntimeConfiguration current = begin(State.RELOADING);
        if (current == null) {
            return ReloadResult.REJECTED;
        }
        try {
            return current.coordinator().reloadIfChanged();
        } finally {
            finishFailed(State.RELOADING);
        }
    }

    @Override
    public LogyardRuntime runtime() {
        return runtime;
    }

    @Override
    public boolean watchesConfiguration() {
        transition.lock();
        try {
            return state != State.CLOSED && active != null && active.watchesConfiguration();
        } finally {
            transition.unlock();
        }
    }

    @Override
    public CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime) {
        ActiveRuntimeConfiguration closing;
        transition.lock();
        try {
            if (state == State.CLOSED) {
                return runtime.retirementCompletion();
            }
            state = State.CLOSED;
            closing = active;
            active = null;
        } finally {
            transition.unlock();
        }

        ComponentFailureCollector failures = new ComponentFailureCollector();
        if (closing != null) {
            ComponentInvocationBoundary.invoke("configuration watcher close", closing::closeWatcher, failures);
        }
        ComponentInvocationBoundary.invoke("installed runtime close", () -> {
            boolean closedByGlobal = false;
            try {
                closedByGlobal = globalRuntime.shutdownIfCurrent(runtime);
            } finally {
                if (!closedByGlobal) {
                    runtime.close();
                }
            }
        }, failures);
        return completionWithFailures(runtime.retirementCompletion(), failures);
    }

    private ActiveRuntimeConfiguration begin(State requested) {
        transition.lock();
        try {
            if (state != State.OPEN) {
                return null;
            }
            state = requested;
            return active;
        } finally {
            transition.unlock();
        }
    }

    private ReloadResult commitReplacement(
            ActiveRuntimeConfiguration expected,
            ActiveRuntimeConfiguration replacement,
            RuntimeAssembly candidate,
            RuntimeAssembly currentAssembly) {
        boolean superseded;
        transition.lock();
        try {
            superseded = state != State.RECONFIGURING || active != expected;
            if (!superseded) {
                runtime.reload(candidate.plan());
                LogyardRuntimeFactory.attach(runtime, candidate);
                active = replacement;
                state = State.OPEN;
            }
        } finally {
            transition.unlock();
        }
        if (superseded) {
            closeReplacement(
                    replacement,
                    candidate,
                    currentAssembly,
                    new IllegalStateException("runtime reconfiguration was superseded by shutdown"));
            return ReloadResult.REJECTED;
        }
        return ReloadResult.APPLIED;
    }

    private ReloadResult finishUnchanged(State expected) {
        transition.lock();
        try {
            if (state != expected) {
                return ReloadResult.REJECTED;
            }
            state = State.OPEN;
            return ReloadResult.UNCHANGED;
        } finally {
            transition.unlock();
        }
    }

    private void finishFailed(State expected) {
        transition.lock();
        try {
            if (state == expected) {
                state = State.OPEN;
            }
        } finally {
            transition.unlock();
        }
    }

    private void restartCurrentWatcher(ActiveRuntimeConfiguration current, Throwable primaryFailure) {
        try {
            ActiveRuntimeConfiguration restarted = current.restartWatcher(this::reloadNow);
            boolean accepted;
            transition.lock();
            try {
                accepted = state == State.RECONFIGURING && active == current;
                if (accepted) {
                    active = restarted;
                }
            } finally {
                transition.unlock();
            }
            if (!accepted) {
                restarted.closeWatcher();
            }
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

    private static CompletionStage<Void> completionWithFailures(
            CompletionStage<Void> retirement,
            ComponentFailureCollector failures) {
        try {
            failures.throwIfPresent("runtime installation close");
            return retirement;
        } catch (RuntimeException failure) {
            return retirement.handle((ignored, retirementFailure) -> {
                if (retirementFailure != null) {
                    failure.addSuppressed(retirementFailure);
                }
                throw new CompletionException(failure);
            });
        }
    }
}
