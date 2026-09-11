package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.zsumz.logyard.runtime.diagnostics.StderrReloadDiagnostics;
import com.zsumz.logyard.runtime.reload.ConfigurationInputs;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.watcher.ConfigurationWatchRegistration;
import com.zsumz.logyard.runtime.reload.watcher.ConfigurationWatcher;
import com.zsumz.logyard.runtime.reload.WatcherReloadOutcome;

import java.util.function.Supplier;

/** Stabilizes mutable configuration bytes and watcher policy before any runtime output is assembled. */
final class ConfigurationSnapshotStabilizer {
    private static final int MAX_ATTEMPTS = 8;

    private ConfigurationSnapshotStabilizer() {
    }

    static StabilizedConfiguration stabilize(
            ConfigurationInstallationRequest request,
            ConfigurationSnapshot initialSnapshot,
            ConfigurationInputs inputs,
            Supplier<WatcherReloadOutcome> reload) {
        ConfigurationSnapshot candidate = initialSnapshot;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ConfigurationWatchRegistration registration = null;
            ConfigurationWatcher watcher = null;
            try {
                ConfigurationWatchHandshake.Selection selection =
                        ConfigurationWatchHandshake.select(request, candidate, inputs);
                registration = selection.registration();
                LogyardConfig config = selection.config();
                ReloadDiagnostics diagnostics = diagnostics(config);
                ConfigurationWatcherPolicy policy = null;
                if (registration != null && config.runtime().watch()) {
                    policy = new ConfigurationWatcherPolicy(
                            config.runtime().reloadDebounce(),
                            config.runtime().shutdownTimeout(),
                            diagnostics);
                    watcher = ConfigurationWatcher.prepare(
                            registration,
                            policy.debounce(),
                            policy.closeTimeout(),
                            reload,
                            policy.diagnostics());
                    registration = null;
                } else {
                    closeRegistration(registration, null);
                    registration = null;
                }

                ConfigurationSnapshot catchUp = request.reloadable()
                        ? PreparedRuntimeConfiguration.read(request)
                        : selection.snapshot();
                if (catchUp.sameContent(selection.snapshot())) {
                    return new StabilizedConfiguration(selection.snapshot(), config, diagnostics, watcher, policy);
                }

                IllegalStateException retry =
                        new IllegalStateException("Logyard configuration changed during preparation attempt " + attempt);
                closeRegistration(registration, retry);
                closeWatcher(watcher, retry);
                registration = null;
                watcher = null;
                if (retry.getSuppressed().length > 0) {
                    throw retry;
                }
                candidate = catchUp;
                if (attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException(
                            "Logyard configuration did not stabilize after " + MAX_ATTEMPTS
                                    + " preparation attempts: " + request.description());
                }
            } catch (RuntimeException | Error failure) {
                closeRegistration(registration, failure);
                closeWatcher(watcher, failure);
                throw failure;
            }
        }
        throw new IllegalStateException("unreachable configuration stabilization state");
    }

    private static ReloadDiagnostics diagnostics(LogyardConfig config) {
        return "off".equals(config.runtime().internalStatus())
                ? ReloadDiagnostics.silent()
                : new StderrReloadDiagnostics(System.err);
    }

    private static void closeRegistration(ConfigurationWatchRegistration registration, Throwable failure) {
        if (registration == null) {
            return;
        }
        try {
            registration.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                throw closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
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
