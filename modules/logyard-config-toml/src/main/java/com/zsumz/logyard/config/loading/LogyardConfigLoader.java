package com.zsumz.logyard.config.loading;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.loading.compiler.ConfigurationCompiler;

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
}
