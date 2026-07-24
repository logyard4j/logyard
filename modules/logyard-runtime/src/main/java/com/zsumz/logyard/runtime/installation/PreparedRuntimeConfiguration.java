package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.zsumz.logyard.runtime.diagnostics.StderrReloadDiagnostics;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.ConfigurationWatcher;
import com.zsumz.logyard.runtime.reload.ReloadCoordinator;
import com.zsumz.logyard.runtime.reload.WatcherReloadOutcome;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

/** Registration-first configuration candidate that owns its watcher and assembled resources until commit. */
final class PreparedRuntimeConfiguration {
    private final ConfigurationInstallationRequest request;
    private final ConfigurationSnapshot snapshot;
    private final RuntimeAssembly assembly;
    private final ReloadDiagnostics diagnostics;
    private final ConfigurationWatcher watcher;
    private final Map<String, String> environment;

    private PreparedRuntimeConfiguration(
            ConfigurationInstallationRequest request,
            ConfigurationSnapshot snapshot,
            RuntimeAssembly assembly,
            ReloadDiagnostics diagnostics,
            ConfigurationWatcher watcher,
            Map<String, String> environment) {
        this.request = request;
        this.snapshot = snapshot;
        this.assembly = assembly;
        this.diagnostics = diagnostics;
        this.watcher = watcher;
        this.environment = Map.copyOf(environment);
    }

    static PreparedRuntimeConfiguration prepare(
            ConfigurationInstallationRequest request,
            ConfigurationSnapshot initialSnapshot,
            RuntimeAssembly currentAssembly,
            Map<String, String> environment,
            Supplier<WatcherReloadOutcome> reload) {
        LogyardConfig selectedConfig = initialSnapshot.parse(environment);
        ReloadDiagnostics diagnostics = diagnostics(selectedConfig);
        ConfigurationWatcher watcher = null;
        RuntimeAssembly assembly = null;
        try {
            watcher = prepareWatcher(request, selectedConfig, reload, diagnostics);
            ConfigurationSnapshot selectedSnapshot = initialSnapshot;
            if (watcher != null) {
                ConfigurationSnapshot registeredSnapshot = read(request);
                if (!registeredSnapshot.sameContent(initialSnapshot)) {
                    selectedSnapshot = registeredSnapshot;
                    selectedConfig = registeredSnapshot.parse(environment);
                }
                if (!selectedConfig.runtime().watch()) {
                    watcher.close();
                    watcher = null;
                }
            }
            assembly = LogyardRuntimeFactory.assemble(selectedConfig, currentAssembly);
            return new PreparedRuntimeConfiguration(request, selectedSnapshot, assembly, diagnostics, watcher, environment);
        } catch (RuntimeException | Error failure) {
            closeWatcher(watcher, failure);
            if (assembly != null) {
                assembly.closeCandidateOutputs(currentAssembly, failure);
            }
            throw failure;
        }
    }

    static ConfigurationSnapshot read(ConfigurationInstallationRequest request) {
        try {
            return request.snapshot();
        } catch (IOException failure) {
            throw new IllegalStateException("failed to read Logyard configuration " + request.description(), failure);
        }
    }

    static ConfigurationWatcher prepareWatcher(
            ConfigurationInstallationRequest request,
            LogyardConfig config,
            Supplier<WatcherReloadOutcome> reload,
            ReloadDiagnostics diagnostics) {
        return config.runtime().watch() && request.watchPath() != null
                ? ConfigurationWatcher.prepare(
                        request.watchPath(),
                        config.runtime().reloadDebounce(),
                        config.runtime().shutdownTimeout(),
                        reload,
                        diagnostics)
                : null;
    }

    ActiveRuntimeConfiguration activate(DefaultLogyardRuntime runtime) {
        ReloadCoordinator coordinator = new ReloadCoordinator(
                request.description(),
                request.watchPath(),
                request.snapshotReader(),
                runtime,
                snapshot,
                assembly,
                diagnostics,
                environment);
        return new ActiveRuntimeConfiguration(request, coordinator, diagnostics, watcher);
    }

    RuntimeAssembly assembly() {
        return assembly;
    }

    void closeWatcher(Throwable failure) {
        closeWatcher(watcher, failure);
    }

    private static ReloadDiagnostics diagnostics(LogyardConfig config) {
        return "off".equals(config.runtime().internalStatus())
                ? ReloadDiagnostics.silent()
                : new StderrReloadDiagnostics(System.err);
    }

    private static void closeWatcher(ConfigurationWatcher watcher, Throwable failure) {
        if (watcher == null) {
            return;
        }
        try {
            watcher.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
