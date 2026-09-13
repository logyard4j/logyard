package com.logyard4j.logyard.runtime.installation;

import com.logyard4j.logyard.runtime.reload.watcher.ConfigurationWatcher;

import java.io.IOException;

/** Arms a prepared watcher when its registration-to-activation window may contain an unobserved change. */
final class ConfigurationWatcherCatchUp {
    private ConfigurationWatcherCatchUp() {
    }

    static void markDirtyIfChanged(
            ConfigurationWatcher watcher,
            String selectedDigest,
            ConfigurationInstallationRequest request) {
        if (watcher == null) {
            return;
        }
        try {
            if (!selectedDigest.equals(request.snapshot().sha256())) {
                watcher.markDirtyBeforeActivation();
            }
        } catch (IOException | RuntimeException readFailure) {
            watcher.markDirtyBeforeActivation();
        }
    }
}
