package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.ConfigurationWatcher;

import java.util.Objects;

/** One stable configuration snapshot and the watch resources derived from exactly those bytes. */
record StabilizedConfiguration(
        ConfigurationSnapshot snapshot,
        LogyardConfig config,
        ReloadDiagnostics diagnostics,
        ConfigurationWatcher watcher,
        ConfigurationWatcherPolicy watcherPolicy) {
    StabilizedConfiguration {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(diagnostics, "diagnostics");
    }

    void closeWatcher(Throwable failure) {
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
