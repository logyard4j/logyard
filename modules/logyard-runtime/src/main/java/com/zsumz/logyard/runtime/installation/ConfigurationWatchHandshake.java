package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.ConfigurationWatchRegistration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Selects a file snapshot across the watcher-registration boundary without requiring watching when disabled. */
final class ConfigurationWatchHandshake {
    private ConfigurationWatchHandshake() {
    }

    static Selection select(
            ConfigurationInstallationRequest request,
            ConfigurationSnapshot initialSnapshot,
            Map<String, String> environment) {
        LogyardConfig initialConfig = initialSnapshot.parse(environment);
        if (!request.reloadable()) {
            return new Selection(initialSnapshot, initialConfig, null);
        }
        if (initialConfig.runtime().watch()) {
            return registerAndReread(request, initialSnapshot, initialConfig, environment);
        }

        ConfigurationSnapshot latestSnapshot = PreparedRuntimeConfiguration.read(request);
        LogyardConfig latestConfig = latestSnapshot.sameContent(initialSnapshot)
                ? initialConfig
                : latestSnapshot.parse(environment);
        if (!latestConfig.runtime().watch()) {
            return new Selection(latestSnapshot, latestConfig, null);
        }
        return registerAndReread(request, latestSnapshot, latestConfig, environment);
    }

    private static Selection registerAndReread(
            ConfigurationInstallationRequest request,
            ConfigurationSnapshot previousSnapshot,
            LogyardConfig previousConfig,
            Map<String, String> environment) {
        ConfigurationWatchRegistration registration = openRegistration(request);
        try {
            ConfigurationSnapshot selectedSnapshot = PreparedRuntimeConfiguration.read(request);
            LogyardConfig selectedConfig = selectedSnapshot.sameContent(previousSnapshot)
                    ? previousConfig
                    : selectedSnapshot.parse(environment);
            return new Selection(selectedSnapshot, selectedConfig, registration);
        } catch (RuntimeException | Error failure) {
            closeRegistration(registration, failure);
            throw failure;
        }
    }

    private static ConfigurationWatchRegistration openRegistration(ConfigurationInstallationRequest request) {
        try {
            return ConfigurationWatchRegistration.open(request.watchPath());
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "failed to establish a Logyard configuration watch for " + request.watchPath(),
                    failure);
        }
    }

    private static void closeRegistration(ConfigurationWatchRegistration registration, Throwable failure) {
        try {
            registration.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    record Selection(
            ConfigurationSnapshot snapshot,
            LogyardConfig config,
            ConfigurationWatchRegistration registration) {
    }
}
