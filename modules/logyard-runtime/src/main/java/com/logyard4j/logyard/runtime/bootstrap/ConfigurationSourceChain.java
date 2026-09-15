package com.logyard4j.logyard.runtime.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Applies deterministic configuration-source precedence and validates every selected location. */
final class ConfigurationSourceChain {
    private static final String CONVENTIONAL_RESOURCE = "logyard.toml";
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final int MAX_FILE_LOCATION_CHARS = 32_768;

    private ConfigurationSourceChain() {
    }

    static Optional<Path> findFile(String property, Map<String, String> environment, Path workingDirectory) {
        Objects.requireNonNull(environment, "environment");
        Path baseDirectory = directory(workingDirectory);
        if (present(property)) {
            return Optional.of(existingFile(resolvePath(baseDirectory, fileLocation(property)), "system property " + ConfigurationDiscovery.SYSTEM_PROPERTY));
        }
        String variable = environment.get(ConfigurationDiscovery.ENVIRONMENT_VARIABLE);
        if (present(variable)) {
            return Optional.of(existingFile(resolvePath(baseDirectory, fileLocation(variable)), "environment variable " + ConfigurationDiscovery.ENVIRONMENT_VARIABLE));
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
            return explicit(property, "system property " + ConfigurationDiscovery.SYSTEM_PROPERTY, loader, baseDirectory);
        }
        String variable = environment.get(ConfigurationDiscovery.ENVIRONMENT_VARIABLE);
        if (present(variable)) {
            return explicit(variable, "environment variable " + ConfigurationDiscovery.ENVIRONMENT_VARIABLE, loader, baseDirectory);
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
        int start = trimStart(location);
        if (location.regionMatches(start, CLASSPATH_PREFIX, 0, CLASSPATH_PREFIX.length())) {
            String resource = ConfigurationSourceFactory.resource(location, start + CLASSPATH_PREFIX.length());
            if (loader.getResource(resource) == null) {
                throw new IllegalStateException("Logyard configuration from " + origin + " does not exist: " + CLASSPATH_PREFIX + resource);
            }
            return LogyardConfigurationSource.classpath(loader, resource, baseDirectory);
        }
        return LogyardConfigurationSource.file(existingFile(resolvePath(baseDirectory, fileLocation(location)), origin));
    }

    private static boolean required(String property, String variable) {
        String value = present(property) ? property : variable;
        if (!present(value)) {
            return false;
        }
        int start = trimStart(value);
        int end = trimEnd(value, start);
        if (end - start == 4 && value.regionMatches(true, start, "true", 0, 4)) {
            return true;
        }
        if (end - start == 5 && value.regionMatches(true, start, "false", 0, 5)) {
            return false;
        }
        String displayed = value.length() <= 128
                ? value
                : value.substring(start, Math.min(end, start + 128)) + (end - start > 128 ? "..." : "");
        throw new IllegalStateException(ConfigurationDiscovery.REQUIRED_SYSTEM_PROPERTY + " must be true or false, but was: " + displayed);
    }

    private static String fileLocation(String value) {
        int start = trimStart(value);
        int end = trimEnd(value, start);
        if (end - start > MAX_FILE_LOCATION_CHARS) {
            throw new IllegalArgumentException("configuration file location exceeds " + MAX_FILE_LOCATION_CHARS + " characters");
        }
        return value.substring(start, end);
    }

    private static int trimStart(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) <= ' ') {
            start++;
        }
        return start;
    }

    private static int trimEnd(String value, int start) {
        int end = value.length();
        while (end > start && value.charAt(end - 1) <= ' ') {
            end--;
        }
        return end;
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

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
