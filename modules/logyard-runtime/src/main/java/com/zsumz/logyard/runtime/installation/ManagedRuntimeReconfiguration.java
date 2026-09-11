package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.reload.ConfigurationInputs;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.WatcherReloadOutcome;

import java.util.Objects;
import java.util.function.Supplier;

import static com.zsumz.logyard.runtime.installation.RuntimeInstallationTransitions.Phase.RECONFIGURING;

/** Owns candidate preparation, publication, rollback, and watcher recovery for one managed runtime. */
final class ManagedRuntimeReconfiguration {
    private final RuntimeInstallationTransitions transitions;
    private final DefaultLogyardRuntime runtime;
    private final ConfigurationInputs inputs;
    private final Supplier<WatcherReloadOutcome> watcherReload;

    ManagedRuntimeReconfiguration(
            RuntimeInstallationTransitions transitions,
            DefaultLogyardRuntime runtime,
            ConfigurationInputs inputs,
            Supplier<WatcherReloadOutcome> watcherReload) {
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.watcherReload = Objects.requireNonNull(watcherReload, "watcherReload");
    }

    ReloadResult reconfigure(ConfigurationInstallationRequest request) {
        ActiveRuntimeConfiguration current = transitions.begin(RECONFIGURING);
        if (current == null) {
            return ReloadResult.REJECTED;
        }

        RuntimeAssembly currentAssembly = current.coordinator().currentAssembly();
        PreparedRuntimeConfiguration prepared = null;
        RuntimeAssembly candidate = null;
        ActiveRuntimeConfiguration replacement = null;
        CurrentWatcherRecovery currentWatcher = new CurrentWatcherRecovery(transitions, watcherReload);
        try {
            ConfigurationSnapshot snapshot = PreparedRuntimeConfiguration.read(request);
            if (current.canReuseImmutableSource(request, snapshot)) {
                return transitions.finish(RECONFIGURING) ? ReloadResult.UNCHANGED : ReloadResult.REJECTED;
            }
            prepared = PreparedRuntimeConfiguration.prepare(request, snapshot, currentAssembly, inputs, watcherReload);
            candidate = prepared.assembly();
            prepared.activateOutputs();
            replacement = prepared.activate(runtime);
            replacement.activateWatcher();
            currentWatcher.retire(current);
            return commitReplacement(current, replacement, candidate, currentAssembly);
        } catch (RuntimeException | Error failure) {
            if (prepared != null && replacement == null) {
                prepared.closeWatcher(failure);
            }
            RuntimeConfigurationCleanup.closeReplacement(replacement, candidate, currentAssembly, failure);
            currentWatcher.restartAfterFailure(current, failure);
            transitions.finish(RECONFIGURING);
            throw failure;
        }
    }

    private ReloadResult commitReplacement(
            ActiveRuntimeConfiguration expected,
            ActiveRuntimeConfiguration replacement,
            RuntimeAssembly candidate,
            RuntimeAssembly currentAssembly) {
        if (!transitions.accepts(RECONFIGURING, expected)) {
            closeSupersededReplacement(replacement, candidate, currentAssembly);
            return ReloadResult.REJECTED;
        }

        runtime.reload(candidate.plan());
        LogyardRuntimeFactory.attach(runtime, candidate);
        if (transitions.replace(RECONFIGURING, expected, replacement)) {
            return ReloadResult.APPLIED;
        }
        closeSupersededReplacement(replacement, candidate, currentAssembly);
        return ReloadResult.REJECTED;
    }

    private static void closeSupersededReplacement(
            ActiveRuntimeConfiguration replacement,
            RuntimeAssembly candidate,
            RuntimeAssembly currentAssembly) {
        RuntimeConfigurationCleanup.closeReplacement(
                replacement,
                candidate,
                currentAssembly,
                new IllegalStateException("runtime reconfiguration was superseded by shutdown"));
    }

}
