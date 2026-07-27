package com.zsumz.logyard.runtime.bootstrap;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Public entry point for deterministic configuration discovery and required-source policy. */
public final class ConfigurationDiscovery {
    /** Explicit configuration location system property. */
    public static final String SYSTEM_PROPERTY = "logyard.config";
    /** Explicit configuration location environment variable. */
    public static final String ENVIRONMENT_VARIABLE = "LOGYARD_CONFIG";
    /** Strict discovery system property. */
    public static final String REQUIRED_SYSTEM_PROPERTY = "logyard.config.required";
    /** Strict discovery environment variable. */
    public static final String REQUIRED_ENVIRONMENT_VARIABLE = "LOGYARD_CONFIG_REQUIRED";

    private ConfigurationDiscovery() {
    }

    /** Resolves configuration in documented precedence order.
     * @return selected configuration source
     */
    public static LogyardConfigurationSource resolve() {
        return resolve(null);
    }

    /** Resolves configuration with an optional framework-provided source.
     * @param frameworkSource optional framework-provided source
     * @return selected configuration source
     */
    public static LogyardConfigurationSource resolve(LogyardConfigurationSource frameworkSource) {
        return resolve(frameworkSource, false);
    }

    /** Resolves configuration with an optional framework source and strictness policy.
     * @param frameworkSource optional framework-provided source
     * @param frameworkRequired whether no discovered source is an error
     * @return selected configuration source
     */
    public static LogyardConfigurationSource resolve(LogyardConfigurationSource frameworkSource, boolean frameworkRequired) {
        return resolve(
                System.getProperty(SYSTEM_PROPERTY),
                System.getProperty(REQUIRED_SYSTEM_PROPERTY),
                System.getenv(),
                contextClassLoader(),
                Path.of("."),
                frameworkSource,
                frameworkRequired);
    }

    /** Finds the legacy explicit-or-working-directory file.
     * @return explicit or working-directory legacy configuration file
     */
    public static Optional<Path> find() {
        return find(System.getProperty(SYSTEM_PROPERTY), System.getenv(), Path.of("."));
    }

    static Optional<Path> find(String property, Map<String, String> environment, Path workingDirectory) {
        return ConfigurationSourceChain.findFile(property, environment, workingDirectory);
    }

    /** Requires the legacy explicit-or-working-directory file.
     * @return required explicit or working-directory legacy configuration file
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
        return resolve(property, requiredProperty, environment, classLoader, workingDirectory, frameworkSource, false);
    }

    static LogyardConfigurationSource resolve(
            String property,
            String requiredProperty,
            Map<String, String> environment,
            ClassLoader classLoader,
            Path workingDirectory,
            LogyardConfigurationSource frameworkSource,
            boolean frameworkRequired) {
        return ConfigurationSourceChain.select(
                property, requiredProperty, environment, classLoader, workingDirectory, frameworkSource, frameworkRequired);
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? ConfigurationDiscovery.class.getClassLoader() : loader;
    }
}
