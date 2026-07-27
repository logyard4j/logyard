package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.core.failure.ComponentFailureCollector;
import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.reload.WatcherReloadOutcome;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import static com.zsumz.logyard.runtime.installation.RuntimeInstallationTransitions.Phase.RELOADING;

public final class ManagedRuntimeInstallation implements RuntimeInstallation {
    private final RuntimeInstallationTransitions transitions = new RuntimeInstallationTransitions();
    private final DefaultLogyardRuntime runtime;
    private final Map<String, String> environment;
    private final ManagedRuntimeReconfiguration reconfiguration;

    private ManagedRuntimeInstallation(DefaultLogyardRuntime runtime, Map<String, String> environment) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.environment = Map.copyOf(environment);
        reconfiguration = new ManagedRuntimeReconfiguration(transitions, runtime, this.environment, this::reloadFromWatcher);
    }

    public static ManagedRuntimeInstallation open(
            ConfigurationInstallationRequest request,
            Map<String, String> environment) {
        DeferredWatcherReload reload = new DeferredWatcherReload();
        PreparedRuntimeConfiguration prepared = PreparedRuntimeConfiguration.prepare(
                request,
                PreparedRuntimeConfiguration.read(request),
                null,
                environment,
                reload);
        DefaultLogyardRuntime runtime = null;
        try {
            prepared.activateOutputs();
            runtime = new DefaultLogyardRuntime(prepared.assembly().plan());
            ManagedRuntimeInstallation installation = new ManagedRuntimeInstallation(runtime, environment);
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
            boolean closedByGlobal = false;
            try {
                closedByGlobal = globalRuntime.shutdownIfCurrent(runtime);
            } finally {
                if (!closedByGlobal) {
                    runtime.close();
                }
            }
        }, failures);
        return RuntimeInstallationClosure.withFailures(runtime.retirementCompletion(), failures);
    }

}
