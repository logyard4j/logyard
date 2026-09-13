package com.logyard4j.logyard.spring.boot.internal.configuration;

import com.logyard4j.logyard.runtime.bootstrap.ConfigurationDiscovery;
import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.logyard.runtime.bootstrap.location.ConfigurationLocationResolver;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.core.env.Environment;

/** Resolves Spring's intentionally small property surface into a framework-neutral source. */
public final class SpringConfigurationResolver {
    private static final String LOGYARD_CONFIG = "logyard.config";
    private static final String LOGYARD_REQUIRED = "logyard.required";
    private static final String LOGGING_CONFIG = "logging.config";

    private SpringConfigurationResolver() {
    }

    public static LogyardConfigurationSource resolve(
            ClassLoader classLoader,
            Environment environment,
            String bootConfigLocation) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(environment, "environment");
        String logyardLocation = value(environment.getProperty(LOGYARD_CONFIG));
        String loggingLocation = value(environment.getProperty(LOGGING_CONFIG));
        String bootLocation = value(bootConfigLocation);
        String alias = reconcileBootLocations(loggingLocation, bootLocation);
        if (logyardLocation != null && alias != null && !logyardLocation.equals(alias)) {
            throw new IllegalStateException(
                    LOGYARD_CONFIG + " and " + LOGGING_CONFIG + " select conflicting locations: "
                            + logyardLocation + " and " + alias);
        }
        String selected = logyardLocation == null ? alias : logyardLocation;
        LogyardConfigurationSource frameworkSource = selected == null ? null : source(classLoader, selected);
        boolean required = environment.getProperty(LOGYARD_REQUIRED, Boolean.class, false);
        return ConfigurationDiscovery.resolve(frameworkSource, required);
    }

    private static String reconcileBootLocations(String environmentLocation, String argumentLocation) {
        if (environmentLocation != null
                && argumentLocation != null
                && !environmentLocation.equals(argumentLocation)) {
            throw new IllegalStateException(
                    LOGGING_CONFIG + " has inconsistent values: "
                            + environmentLocation + " and " + argumentLocation);
        }
        return environmentLocation == null ? argumentLocation : environmentLocation;
    }

    private static LogyardConfigurationSource source(ClassLoader classLoader, String location) {
        return ConfigurationLocationResolver.resolve(
                "Logyard",
                classLoader,
                location,
                Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
    }

    private static String value(String candidate) {
        return candidate == null || candidate.isBlank() ? null : candidate.trim();
    }
}
