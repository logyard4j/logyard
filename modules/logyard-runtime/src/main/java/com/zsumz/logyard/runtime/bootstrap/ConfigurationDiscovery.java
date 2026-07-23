package com.zsumz.logyard.runtime.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic configuration discovery with no silent classpath magic. */
public final class ConfigurationDiscovery {
    public static final String SYSTEM_PROPERTY = "logyard.config";
    public static final String ENVIRONMENT_VARIABLE = "LOGYARD_CONFIG";

    private ConfigurationDiscovery() {
    }

    public static Optional<Path> find() {
        return find(System.getProperties().getProperty(SYSTEM_PROPERTY), System.getenv(), Path.of("."));
    }

    static Optional<Path> find(String property, Map<String, String> environment, Path workingDirectory) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        if (property != null && !property.isBlank()) {
            return Optional.of(existing(Path.of(property), "system property " + SYSTEM_PROPERTY));
        }
        String variable = environment.get(ENVIRONMENT_VARIABLE);
        if (variable != null && !variable.isBlank()) {
            return Optional.of(existing(Path.of(variable), "environment variable " + ENVIRONMENT_VARIABLE));
        }
        Path conventional = workingDirectory.resolve("logyard.toml").toAbsolutePath().normalize();
        return Files.isRegularFile(conventional) ? Optional.of(conventional) : Optional.empty();
    }

    public static Path require() {
        return find().orElseThrow(() -> new IllegalStateException(
                "Logyard configuration not found. Set -D" + SYSTEM_PROPERTY + "=/path/logyard.toml, "
                        + ENVIRONMENT_VARIABLE + ", or create ./logyard.toml"));
    }

    private static Path existing(Path candidate, String source) {
        Path absolute = candidate.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalStateException("Logyard configuration from " + source + " does not exist: " + absolute);
        }
        return absolute;
    }
}
