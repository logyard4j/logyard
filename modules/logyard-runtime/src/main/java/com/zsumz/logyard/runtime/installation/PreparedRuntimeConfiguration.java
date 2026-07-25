package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.zsumz.logyard.runtime.diagnostics.StderrReloadDiagnostics;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.ConfigurationWatchRegistration;
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
    private final ConfigurationWatcherPolicy watcherPolicy;
    private final Map<String, String> environment;

    private PreparedRuntimeConfiguration(
            ConfigurationInstallationRequest request,
            ConfigurationSnapshot snapshot,
            RuntimeAssembly assembly,
            ReloadDiagnostics diagnostics,
            ConfigurationWatcher watcher,
            ConfigurationWatcherPolicy watcherPolicy,
            Map<String, String> environment) {
        this.request = request;
        this.snapshot = snapshot;
        this.assembly = assembly;
        this.diagnostics = diagnostics;
        this.watcher = watcher;
        this.watcherPolicy = watcherPolicy;
        this.environment = Map.copyOf(environment);
    }

    static PreparedRuntimeConfiguration prepare(
            ConfigurationInstallationRequest request,
            ConfigurationSnapshot initialSnapshot,
            RuntimeAssembly currentAssembly,
            Map<String, String> environment,
            Supplier<WatcherReloadOutcome> reload) {
        ConfigurationWatchRegistration registration = null;
        ConfigurationWatcher watcher = null;
        ConfigurationWatcherPolicy watcherPolicy = null;
        RuntimeAssembly assembly = null;
        try {
            ConfigurationWatchHandshake.Selection selection =
                    ConfigurationWatchHandshake.select(request, initialSnapshot, environment);
            registration = selection.registration();
            ConfigurationSnapshot selectedSnapshot = selection.snapshot();
            LogyardConfig selectedConfig = selection.config();

            ReloadDiagnostics diagnostics = diagnostics(selectedConfig);
            if (registration != null && selectedConfig.runtime().watch()) {
                watcherPolicy = new ConfigurationWatcherPolicy(
                        selectedConfig.runtime().reloadDebounce(),
                        selectedConfig.runtime().shutdownTimeout(),
                        diagnostics);
                watcher = ConfigurationWatcher.prepare(
                        registration,
                        watcherPolicy.debounce(),
                        watcherPolicy.closeTimeout(),
                        reload,
                        watcherPolicy.diagnostics());
                ConfigurationWatcherCatchUp.markDirtyIfChanged(watcher, selectedSnapshot.sha256(), request);
                registration = null;
            } else {
                closeRegistration(registration);
                registration = null;
            }
            assembly = LogyardRuntimeFactory.assemble(selectedConfig, currentAssembly);
            return new PreparedRuntimeConfiguration(
                    request,
                    selectedSnapshot,
                    assembly,
                    diagnostics,
                    watcher,
                    watcherPolicy,
                    environment);
        } catch (RuntimeException | Error failure) {
            closeRegistration(registration, failure);
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

    ConfigurationWatcherPolicy watcherPolicy() {
        return watcherPolicy;
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

    private static void closeRegistration(ConfigurationWatchRegistration registration) {
        if (registration != null) {
            registration.close();
        }
    }

    private static void closeRegistration(ConfigurationWatchRegistration registration, Throwable failure) {
        if (registration == null) {
            return;
        }
        try {
            registration.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
