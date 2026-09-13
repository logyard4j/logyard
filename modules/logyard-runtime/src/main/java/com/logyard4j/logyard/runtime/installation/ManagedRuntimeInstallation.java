package com.logyard4j.logyard.runtime.installation;

import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.reload.ReloadResult;
import com.logyard4j.logyard.core.failure.ComponentFailureCollector;
import com.logyard4j.logyard.core.failure.ComponentInvocationBoundary;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.logyard.runtime.reload.WatcherReloadOutcome;
import com.logyard4j.logyard.runtime.reload.ConfigurationInputs;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import static com.logyard4j.logyard.runtime.installation.RuntimeInstallationTransitions.Phase.RELOADING;

public final class ManagedRuntimeInstallation implements RuntimeInstallation {
    private final RuntimeInstallationTransitions transitions = new RuntimeInstallationTransitions();
    private final DefaultLogyardRuntime runtime;
    private final ManagedRuntimeReconfiguration reconfiguration;

    private ManagedRuntimeInstallation(DefaultLogyardRuntime runtime, ConfigurationInputs inputs) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        reconfiguration = new ManagedRuntimeReconfiguration(transitions, runtime, inputs, this::reloadFromWatcher);
    }

    public static ManagedRuntimeInstallation open(
            ConfigurationInstallationRequest request,
            Map<String, String> environment) {
        ConfigurationInputs inputs = ConfigurationInputs.capture(environment);
        DeferredWatcherReload reload = new DeferredWatcherReload();
        PreparedRuntimeConfiguration prepared = PreparedRuntimeConfiguration.prepare(
                request,
                PreparedRuntimeConfiguration.read(request),
                null,
                inputs,
                reload);
        DefaultLogyardRuntime runtime = null;
        try {
            prepared.activateOutputs();
            runtime = new DefaultLogyardRuntime(prepared.assembly().plan());
            ManagedRuntimeInstallation installation = new ManagedRuntimeInstallation(runtime, inputs);
            reload.bind(installation::reloadFromWatcher);
            LogyardRuntimeFactory.attach(runtime, prepared.assembly());
            ActiveRuntimeConfiguration active = prepared.activate(runtime);
            installation.transitions.initialize(active);
            active.activateWatcher();
            return installation;
        } catch (RuntimeException | Error failure) {
            prepared.closeWatcher(failure);
            ComponentFailureCollector cleanupFailures = new ComponentFailureCollector();
            DefaultLogyardRuntime failedRuntime = runtime;
            if (failedRuntime == null) {
                prepared.assembly().closeCandidateOutputs(null, failure);
            } else {
                ComponentInvocationBoundary.invoke("failed installation runtime close", failedRuntime::close, cleanupFailures);
            }
            cleanupFailures.suppressInto(failure);
            throw failure;
        }
    }

    @Override
    public ReloadResult reconfigure(ConfigurationInstallationRequest request) {
        return reconfiguration.reconfigure(request);
    }

    @Override
    public ReloadResult reloadNow() {
        ActiveRuntimeConfiguration current = transitions.begin(RELOADING);
        if (current == null) {
            return ReloadResult.REJECTED;
        }
        try {
            return current.coordinator().reloadIfChanged();
        } finally {
            transitions.finish(RELOADING);
        }
    }

    private WatcherReloadOutcome reloadFromWatcher() {
        ActiveRuntimeConfiguration current = transitions.begin(RELOADING);
        if (current == null) {
            return transitions.closed() ? WatcherReloadOutcome.INVALID_CANDIDATE : WatcherReloadOutcome.BUSY_RETRY;
        }
        try {
            return current.coordinator().reloadForWatcher();
        } finally {
            transitions.finish(RELOADING);
        }
    }

    @Override
    public LogyardRuntime runtime() {
        return runtime;
    }

    @Override
    public boolean watchesConfiguration() {
        ActiveRuntimeConfiguration current = transitions.current();
        return !transitions.closed() && current != null && current.watchesConfiguration();
    }

    @Override
    public Duration shutdownTimeout() {
        ActiveRuntimeConfiguration current = transitions.current();
        return current == null
                ? RuntimeInstallation.super.shutdownTimeout()
                : current.coordinator().currentConfig().runtime().shutdownTimeout();
    }

    @Override
    public CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime) {
        RuntimeInstallationTransitions.CloseTransition closing = transitions.close();
        if (!closing.changed()) {
            return runtime.retirementCompletion();
        }

        ComponentFailureCollector failures = new ComponentFailureCollector();
        if (closing.active() != null) {
            ComponentInvocationBoundary.invoke("configuration watcher close", closing.active()::closeWatcher, failures);
        }
        ComponentInvocationBoundary.invoke("installed runtime close", () -> {
            if (!globalRuntime.shutdownIfCurrent(runtime)) {
                runtime.close();
            }
        }, failures);
        return RuntimeInstallationClosure.withFailures(runtime.retirementCompletion(), failures);
    }

}
