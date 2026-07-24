package com.zsumz.logyard.quarkus.runtime.configuration;

import com.zsumz.logyard.runtime.bootstrap.ConfigurationDiscovery;
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;

import java.net.URI;
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
        Path baseDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (location.startsWith("classpath:")) {
            return LogyardConfigurationSource.classpath(
                    classLoader,
                    location.substring("classpath:".length()),
                    baseDirectory);
        }
        if (location.startsWith("file:")) {
            return LogyardConfigurationSource.file(Path.of(URI.create(location)));
        }
        if (hasScheme(location)) {
            throw new IllegalStateException(
                    "Quarkus Logyard configuration supports classpath: and filesystem locations, but was: " + location);
        }
        Path path = Path.of(location);
        return LogyardConfigurationSource.file(path.isAbsolute() ? path : baseDirectory.resolve(path));
    }

    private static boolean hasScheme(String location) {
        int separator = location.indexOf(':');
        if (separator <= 1) {
            return false;
        }
        for (int index = 0; index < separator; index++) {
            char character = location.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '+' && character != '-' && character != '.') {
                return false;
            }
        }
        return true;
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? QuarkusConfigurationResolver.class.getClassLoader() : loader;
    }
}
