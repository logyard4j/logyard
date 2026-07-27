package com.zsumz.logyard.quarkus.runtime.configuration;

import com.zsumz.logyard.runtime.bootstrap.ConfigurationDiscovery;
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.bootstrap.location.ConfigurationLocationResolver;

import java.nio.file.Path;
import java.util.Objects;

/** Resolves Quarkus properties into Logyard's framework-neutral configuration source. */
public final class QuarkusConfigurationResolver {
    private QuarkusConfigurationResolver() {
    }

    /**
     * Resolves explicit process settings, the Quarkus location, conventional sources, and defaults
     * in Logyard's documented precedence order.
     *
     * @param configuration Quarkus runtime configuration
     * @return resolved Logyard source
     */
    public static LogyardConfigurationSource resolve(LogyardQuarkusRuntimeConfig configuration) {
        Objects.requireNonNull(configuration, "configuration");
        LogyardConfigurationSource frameworkSource = configuration.config()
                .map(String::trim)
                .filter(location -> !location.isEmpty())
                .map(location -> source(contextClassLoader(), location))
                .orElse(null);
        return ConfigurationDiscovery.resolve(frameworkSource, configuration.required());
    }

    private static LogyardConfigurationSource source(ClassLoader classLoader, String location) {
        return ConfigurationLocationResolver.resolve(
                "Quarkus Logyard",
                classLoader,
                location,
                Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? QuarkusConfigurationResolver.class.getClassLoader() : loader;
    }
}
