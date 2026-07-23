package com.zsumz.logyard.runtime.bootstrap;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.zsumz.logyard.runtime.diagnostics.StderrReloadDiagnostics;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.ConfigurationWatcher;
import com.zsumz.logyard.runtime.reload.ReloadCoordinator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Explicit process bootstrap used by applications and the SLF4J provider. */
public final class LogyardBootstrap {
    private LogyardBootstrap() {
    }

    public static RuntimeBundle start() {
        return start(ConfigurationDiscovery.require());
    }

    public static RuntimeBundle start(Path source) {
        Objects.requireNonNull(source, "source");
        ConfigurationSnapshot snapshot;
        try {
            snapshot = ConfigurationSnapshot.read(source);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to read Logyard configuration " + source, failure);
        }
        Map<String, String> environment = System.getenv();
        LogyardConfig config = snapshot.parse(environment);
        RuntimeAssembly assembly = LogyardRuntimeFactory.assemble(config, null);
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(assembly.plan());
        LogyardRuntimeFactory.attach(runtime, assembly);
        boolean initialized = false;
        try {
            Logyard.initialize(runtime);
            initialized = true;
            ReloadDiagnostics diagnostics = "off".equals(config.runtime().internalStatus())
                    ? ReloadDiagnostics.silent()
                    : new StderrReloadDiagnostics(System.err);
            ReloadCoordinator coordinator = new ReloadCoordinator(
                    snapshot.source(),
                    runtime,
                    snapshot,
                    assembly,
                    diagnostics,
                    environment);
            ConfigurationWatcher watcher = config.runtime().watch()
                    ? ConfigurationWatcher.start(
                            snapshot.source(),
                            config.runtime().reloadDebounce(),
                            config.runtime().shutdownTimeout(),
                            coordinator,
                            diagnostics)
                    : null;
            return new RuntimeBundle(snapshot.source(), runtime, coordinator, watcher);
        } catch (RuntimeException | Error failure) {
            if (initialized && Logyard.runtimeOrNull() == runtime) {
                try {
                    Logyard.shutdown();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            } else {
                try {
                    runtime.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }
}
