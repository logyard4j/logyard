package com.zsumz.logyard.config.loading;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.loading.compiler.ConfigurationCompiler;
import com.zsumz.logyard.config.loading.overlay.ConfigOverlays;
import com.zsumz.logyard.config.loading.result.LoadedConfiguration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Stable entry point for strict TOML configuration loading. */
public final class LogyardConfigLoader {
    public static final int MAX_CONFIG_BYTES = ConfigurationCompiler.MAX_CONFIG_BYTES;
    public static final int MAX_EXPANDED_STRING_CHARS = ConfigurationCompiler.MAX_EXPANDED_STRING_CHARS;

    private LogyardConfigLoader() {
    }

    public static LogyardConfig load(Path path) throws IOException {
        return ConfigurationCompiler.load(path);
    }

    /**
     * Loads one file with explicit overlays, validating every declared profile and
     * returning the selected variant with its value origins.
     */
    public static LoadedConfiguration loadDetailed(Path path, ConfigOverlays overlays) throws IOException {
        return ConfigurationCompiler.loadDetailed(path, overlays);
    }

    public static LogyardConfig parse(String text, String source, Path baseDirectory) {
        return ConfigurationCompiler.parse(text, source, baseDirectory);
    }

    public static LogyardConfig parse(
            String text,
            String source,
            Path baseDirectory,
            Map<String, String> environment) {
        return ConfigurationCompiler.parse(text, source, baseDirectory, environment);
    }

    /**
     * Parses with explicit overlays, validating every declared profile and returning
     * the selected variant with its value origins.
     */
    public static LoadedConfiguration parseDetailed(
            String text,
            String source,
            Path baseDirectory,
            Map<String, String> environment,
            ConfigOverlays overlays) {
        return ConfigurationCompiler.parseDetailed(text, source, baseDirectory, environment, overlays);
    }
}
