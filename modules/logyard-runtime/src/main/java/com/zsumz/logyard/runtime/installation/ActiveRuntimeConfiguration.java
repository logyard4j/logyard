package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.zsumz.logyard.runtime.diagnostics.StderrReloadDiagnostics;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.ConfigurationWatcher;
import com.zsumz.logyard.runtime.reload.ReloadCoordinator;

import java.util.Map;
import java.util.function.Supplier;

final class ActiveRuntimeConfiguration {
    private final ConfigurationInstallationRequest request;
    private final ReloadCoordinator coordinator;
    private final ReloadDiagnostics diagnostics;
    private final ConfigurationWatcher watcher;

    private ActiveRuntimeConfiguration(
            ConfigurationInstallationRequest request,
            ReloadCoordinator coordinator,
            ReloadDiagnostics diagnostics,
            ConfigurationWatcher watcher) {
        this.request = request;
        this.coordinator = coordinator;
        this.diagnostics = diagnostics;
        this.watcher = watcher;
    }

    static ActiveRuntimeConfiguration prepare(
            ConfigurationInstallationRequest request,
            DefaultLogyardRuntime runtime,
            ConfigurationSnapshot snapshot,
            RuntimeAssembly assembly,
            Map<String, String> environment,
            Supplier<ReloadResult> reload) {
        LogyardConfig config = assembly.config();
        ReloadDiagnostics diagnostics = "off".equals(config.runtime().internalStatus())
                ? ReloadDiagnostics.silent()
                : new StderrReloadDiagnostics(System.err);
        ReloadCoordinator coordinator = new ReloadCoordinator(
                request.description(),
                request.watchPath(),
                request.snapshotReader(),
                runtime,
                snapshot,
                assembly,
                diagnostics,
                environment);
        ConfigurationWatcher watcher = config.runtime().watch() && request.watchPath() != null
                ? ConfigurationWatcher.prepare(
                        request.watchPath(),
                        config.runtime().reloadDebounce(),
                        config.runtime().shutdownTimeout(),
                        reload,
                        diagnostics)
                : null;
        return new ActiveRuntimeConfiguration(request, coordinator, diagnostics, watcher);
    }

    ActiveRuntimeConfiguration restartWatcher(Supplier<ReloadResult> reload) {
        LogyardConfig config = coordinator.currentConfig();
        ConfigurationWatcher replacement = config.runtime().watch() && request.watchPath() != null
                ? ConfigurationWatcher.prepare(
                        request.watchPath(),
                        config.runtime().reloadDebounce(),
                        config.runtime().shutdownTimeout(),
                        reload,
                        diagnostics)
                : null;
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
}
