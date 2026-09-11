package com.logyard4j.runtime.installation;

import com.logyard4j.runtime.diagnostics.ReloadDiagnostics;
import com.logyard4j.runtime.reload.ConfigurationSnapshot;
import com.logyard4j.runtime.reload.coordination.ReloadCoordinator;
import com.logyard4j.runtime.reload.watcher.ConfigurationWatcher;
import com.logyard4j.runtime.reload.WatcherReloadOutcome;

import java.util.function.Supplier;

final class ActiveRuntimeConfiguration {
    private final ConfigurationInstallationRequest request;
    private final ReloadCoordinator coordinator;
    private final ReloadDiagnostics diagnostics;
    private final ConfigurationWatcher watcher;

    ActiveRuntimeConfiguration(
            ConfigurationInstallationRequest request,
            ReloadCoordinator coordinator,
            ReloadDiagnostics diagnostics,
            ConfigurationWatcher watcher) {
        this.request = request;
        this.coordinator = coordinator;
        this.diagnostics = diagnostics;
        this.watcher = watcher;
    }

    ActiveRuntimeConfiguration restartWatcher(Supplier<WatcherReloadOutcome> reload) {
        ConfigurationWatcher replacement = PreparedRuntimeConfiguration.prepareWatcher(
                request,
                coordinator.currentConfig(),
                reload,
                diagnostics);
        ConfigurationWatcherCatchUp.markDirtyIfChanged(replacement, coordinator.currentDigest(), request);
        ActiveRuntimeConfiguration restarted = new ActiveRuntimeConfiguration(request, coordinator, diagnostics, replacement);
        restarted.activateWatcher();
        return restarted;
    }

    void activateWatcher() {
        if (watcher != null) {
            watcher.activate();
        }
    }

    void closeWatcher() {
        if (watcher != null) {
            watcher.close();
        }
    }

    boolean canReuseImmutableSource(ConfigurationInstallationRequest candidate, ConfigurationSnapshot snapshot) {
        return !request.reloadable()
                && !candidate.reloadable()
                && request.identifiesSameSource(candidate)
                && coordinator.currentDigest().equals(snapshot.sha256());
    }

    boolean watchesConfiguration() {
        return watcher != null && watcher.isRunning();
    }

    ReloadCoordinator coordinator() {
        return coordinator;
    }
}
