package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.ConfigurationWatcher;
import com.zsumz.logyard.runtime.reload.ReloadCoordinator;
import com.zsumz.logyard.runtime.reload.WatcherReloadOutcome;

import java.io.IOException;
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
        markDirtyWhenRegistrationMissedAChange(replacement);
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

    boolean sameSourceAndDigest(ConfigurationInstallationRequest candidate, ConfigurationSnapshot snapshot) {
        return request.identifiesSameSource(candidate) && coordinator.currentDigest().equals(snapshot.sha256());
    }

    boolean watchesConfiguration() {
        return watcher != null;
    }

    ReloadCoordinator coordinator() {
        return coordinator;
    }

    private void markDirtyWhenRegistrationMissedAChange(ConfigurationWatcher replacement) {
        if (replacement == null) {
            return;
        }
        try {
            if (!coordinator.currentDigest().equals(request.snapshot().sha256())) {
                replacement.markDirtyBeforeActivation();
            }
        } catch (IOException | RuntimeException readFailure) {
            replacement.markDirtyBeforeActivation();
        }
    }
}
