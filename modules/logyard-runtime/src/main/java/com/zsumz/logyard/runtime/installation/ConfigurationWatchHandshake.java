package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.runtime.reload.ConfigurationInputs;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.watcher.ConfigurationWatchRegistration;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Selects a file snapshot across the watcher-registration boundary without requiring watching when disabled. */
final class ConfigurationWatchHandshake {
    private ConfigurationWatchHandshake() {
    }

    static Selection select(
            ConfigurationInstallationRequest request,
            ConfigurationSnapshot initialSnapshot,
            ConfigurationInputs inputs) {
        LogyardConfig initialConfig = inputs.parse(initialSnapshot);
        if (!request.reloadable()) {
            return new Selection(initialSnapshot, initialConfig, null);
        }
        if (initialConfig.runtime().watch()) {
            return registerAndReread(request, initialSnapshot, initialConfig, inputs);
        }

        ConfigurationSnapshot latestSnapshot = PreparedRuntimeConfiguration.read(request);
        LogyardConfig latestConfig = latestSnapshot.sameContent(initialSnapshot)
                ? initialConfig
                : inputs.parse(latestSnapshot);
        if (!latestConfig.runtime().watch()) {
            return new Selection(latestSnapshot, latestConfig, null);
        }
        return registerAndReread(request, latestSnapshot, latestConfig, inputs);
    }

    private static Selection registerAndReread(
            ConfigurationInstallationRequest request,
            ConfigurationSnapshot previousSnapshot,
            LogyardConfig previousConfig,
            ConfigurationInputs inputs) {
        ConfigurationWatchRegistration registration = openRegistration(request);
        try {
            ConfigurationSnapshot selectedSnapshot = PreparedRuntimeConfiguration.read(request);
            LogyardConfig selectedConfig = selectedSnapshot.sameContent(previousSnapshot)
                    ? previousConfig
                    : inputs.parse(selectedSnapshot);
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
