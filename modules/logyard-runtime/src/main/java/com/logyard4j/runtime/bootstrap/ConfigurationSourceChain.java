package com.logyard4j.runtime.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Applies deterministic configuration-source precedence and validates every selected location. */
final class ConfigurationSourceChain {
    private static final String CONVENTIONAL_RESOURCE = "logyard.toml";

    private ConfigurationSourceChain() {
    }

    static Optional<Path> findFile(String property, Map<String, String> environment, Path workingDirectory) {
        Objects.requireNonNull(environment, "environment");
        Path baseDirectory = directory(workingDirectory);
        if (present(property)) {
            return Optional.of(existingFile(resolvePath(baseDirectory, property.trim()), "system property " + ConfigurationDiscovery.SYSTEM_PROPERTY));
        }
        String variable = environment.get(ConfigurationDiscovery.ENVIRONMENT_VARIABLE);
        if (present(variable)) {
            return Optional.of(existingFile(resolvePath(baseDirectory, variable.trim()), "environment variable " + ConfigurationDiscovery.ENVIRONMENT_VARIABLE));
        }
        Path conventional = baseDirectory.resolve(CONVENTIONAL_RESOURCE);
        return Files.isRegularFile(conventional) ? Optional.of(conventional) : Optional.empty();
    }

    static LogyardConfigurationSource select(
            String property,
            String requiredProperty,
            Map<String, String> environment,
            ClassLoader classLoader,
            Path workingDirectory,
            LogyardConfigurationSource frameworkSource,
            boolean frameworkRequired) {
        Objects.requireNonNull(environment, "environment");
        ClassLoader loader = Objects.requireNonNull(classLoader, "classLoader");
        Path baseDirectory = directory(workingDirectory);
        if (present(property)) {
            return explicit(property.trim(), "system property " + ConfigurationDiscovery.SYSTEM_PROPERTY, loader, baseDirectory);
        }
        String variable = environment.get(ConfigurationDiscovery.ENVIRONMENT_VARIABLE);
        if (present(variable)) {
            return explicit(variable.trim(), "environment variable " + ConfigurationDiscovery.ENVIRONMENT_VARIABLE, loader, baseDirectory);
        }
        if (frameworkSource != null) {
            return frameworkSource;
        }
        if (loader.getResource(CONVENTIONAL_RESOURCE) != null) {
            return LogyardConfigurationSource.classpath(loader, CONVENTIONAL_RESOURCE, baseDirectory);
        }
        Path conventional = baseDirectory.resolve(CONVENTIONAL_RESOURCE);
        if (Files.isRegularFile(conventional)) {
            return LogyardConfigurationSource.file(conventional);
        }
        if (frameworkRequired || required(requiredProperty, environment.get(ConfigurationDiscovery.REQUIRED_ENVIRONMENT_VARIABLE))) {
            throw new IllegalStateException(
                    "Logyard configuration is required but none was found in explicit settings, framework handoff, classpath:"
                            + CONVENTIONAL_RESOURCE + ", or " + conventional);
        }
        return LogyardConfigurationSource.defaults(baseDirectory);
    }

    private static LogyardConfigurationSource explicit(
            String location,
            String origin,
            ClassLoader loader,
            Path baseDirectory) {
        if (location.startsWith("classpath:")) {
            String resource = stripLeadingSlashes(location.substring("classpath:".length()));
            if (loader.getResource(resource) == null) {
                throw new IllegalStateException("Logyard configuration from " + origin + " does not exist: " + location);
            }
            return LogyardConfigurationSource.classpath(loader, resource, baseDirectory);
        }
        return LogyardConfigurationSource.file(existingFile(resolvePath(baseDirectory, location), origin));
    }

    private static boolean required(String property, String variable) {
        String value = present(property) ? property : variable;
        if (!present(value)) {
            return false;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        throw new IllegalStateException(ConfigurationDiscovery.REQUIRED_SYSTEM_PROPERTY + " must be true or false, but was: " + value);
    }

    private static Path existingFile(Path candidate, String source) {
        Path absolute = candidate.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalStateException("Logyard configuration from " + source + " does not exist: " + absolute);
        }
        return absolute;
    }

    private static Path resolvePath(Path workingDirectory, String location) {
        Path candidate = Path.of(location);
        return candidate.isAbsolute() ? candidate.normalize() : workingDirectory.resolve(candidate).normalize();
    }

    private static Path directory(Path directory) {
        return Objects.requireNonNull(directory, "workingDirectory").toAbsolutePath().normalize();
    }

    private static String stripLeadingSlashes(String value) {
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
