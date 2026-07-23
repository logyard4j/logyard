package com.zsumz.logyard.runtime.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic configuration discovery with explicit precedence and bounded safe defaults. */
public final class ConfigurationDiscovery {
    /** Explicit configuration location system property. */
    public static final String SYSTEM_PROPERTY = "logyard.config";

    /** Explicit configuration location environment variable. */
    public static final String ENVIRONMENT_VARIABLE = "LOGYARD_CONFIG";

    /** Strict discovery system property. */
    public static final String REQUIRED_SYSTEM_PROPERTY = "logyard.config.required";

    /** Strict discovery environment variable. */
    public static final String REQUIRED_ENVIRONMENT_VARIABLE = "LOGYARD_CONFIG_REQUIRED";

    private static final String CONVENTIONAL_RESOURCE = "logyard.toml";

    private ConfigurationDiscovery() {
    }

    /**
     * Resolves configuration in documented precedence order.
     *
     * @return selected configuration source
     */
    public static LogyardConfigurationSource resolve() {
        return resolve(null);
    }

    /**
     * Resolves configuration with a framework-provided source below explicit user settings and
     * above conventional classpath and working-directory locations.
     *
     * @param frameworkSource optional framework-provided source
     * @return selected configuration source
     */
    public static LogyardConfigurationSource resolve(LogyardConfigurationSource frameworkSource) {
        return resolve(
                System.getProperty(SYSTEM_PROPERTY),
                System.getProperty(REQUIRED_SYSTEM_PROPERTY),
                System.getenv(),
                contextClassLoader(),
                Path.of("."),
                frameworkSource);
    }

    /**
     * Finds the legacy file-only configuration location.
     *
     * @return explicit or working-directory file
     */
    public static Optional<Path> find() {
        return find(System.getProperty(SYSTEM_PROPERTY), System.getenv(), Path.of("."));
    }

    static Optional<Path> find(String property, Map<String, String> environment, Path workingDirectory) {
        Objects.requireNonNull(environment, "environment");
        Path base = normalizedDirectory(workingDirectory);
        if (present(property)) {
            return Optional.of(existingFile(resolvePath(base, property.trim()), "system property " + SYSTEM_PROPERTY));
        }
        String variable = environment.get(ENVIRONMENT_VARIABLE);
        if (present(variable)) {
            return Optional.of(existingFile(resolvePath(base, variable.trim()), "environment variable " + ENVIRONMENT_VARIABLE));
        }
        Path conventional = base.resolve(CONVENTIONAL_RESOURCE);
        return Files.isRegularFile(conventional) ? Optional.of(conventional) : Optional.empty();
    }

    /**
     * Requires the legacy file-only configuration location.
     *
     * @return explicit or working-directory configuration path
     */
    public static Path require() {
        return find().orElseThrow(() -> new IllegalStateException(
                "Logyard configuration not found. Set -D" + SYSTEM_PROPERTY + "=/path/logyard.toml, "
                        + ENVIRONMENT_VARIABLE + ", or create ./logyard.toml"));
    }

    static LogyardConfigurationSource resolve(
            String property,
            String requiredProperty,
            Map<String, String> environment,
            ClassLoader classLoader,
            Path workingDirectory,
            LogyardConfigurationSource frameworkSource) {
        Objects.requireNonNull(environment, "environment");
        ClassLoader loader = Objects.requireNonNull(classLoader, "classLoader");
        Path base = normalizedDirectory(workingDirectory);

        if (present(property)) {
            return explicitSource(property.trim(), "system property " + SYSTEM_PROPERTY, loader, base);
        }
        String variable = environment.get(ENVIRONMENT_VARIABLE);
        if (present(variable)) {
            return explicitSource(variable.trim(), "environment variable " + ENVIRONMENT_VARIABLE, loader, base);
        }
        if (frameworkSource != null) {
            return frameworkSource;
        }
        if (loader.getResource(CONVENTIONAL_RESOURCE) != null) {
            return LogyardConfigurationSource.classpath(loader, CONVENTIONAL_RESOURCE, base);
        }
        Path conventional = base.resolve(CONVENTIONAL_RESOURCE);
        if (Files.isRegularFile(conventional)) {
            return LogyardConfigurationSource.file(conventional);
        }
        String requiredVariable = environment.get(REQUIRED_ENVIRONMENT_VARIABLE);
        if (required(requiredProperty, requiredVariable)) {
            throw new IllegalStateException(
                    "Logyard configuration is required but none was found in explicit settings, framework handoff, classpath:"
                            + CONVENTIONAL_RESOURCE + ", or " + conventional);
        }
        return LogyardConfigurationSource.defaults(base);
    }

    private static LogyardConfigurationSource explicitSource(
            String location,
            String origin,
            ClassLoader loader,
            Path baseDirectory) {
        if (location.startsWith("classpath:")) {
            String resource = location.substring("classpath:".length());
            if (loader.getResource(stripLeadingSlashes(resource)) == null) {
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
        throw new IllegalStateException(REQUIRED_SYSTEM_PROPERTY + " must be true or false, but was: " + value);
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

    private static Path normalizedDirectory(Path directory) {
        return Objects.requireNonNull(directory, "workingDirectory").toAbsolutePath().normalize();
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? ConfigurationDiscovery.class.getClassLoader() : loader;
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
