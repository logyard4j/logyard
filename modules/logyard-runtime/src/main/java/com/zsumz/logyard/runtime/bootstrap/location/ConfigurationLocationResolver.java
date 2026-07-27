package com.zsumz.logyard.runtime.bootstrap.location;

import com.zsumz.logyard.api.annotation.InternalApi;
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/** Resolves the portable location syntax shared by framework configuration handoffs. */
@InternalApi
public final class ConfigurationLocationResolver {
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILE_PREFIX = "file:";

    private ConfigurationLocationResolver() {
    }

    /**
     * Resolves a classpath, file URI, absolute filesystem, or base-directory-relative location.
     *
     * @param configurationName human-readable configuration owner used in diagnostics
     * @param classLoader class loader for classpath locations
     * @param location nonblank framework configuration location
     * @param baseDirectory base for relative filesystem locations and relative output paths
     * @return framework-neutral configuration source
     */
    public static LogyardConfigurationSource resolve(
            String configurationName,
            ClassLoader classLoader,
            String location,
            Path baseDirectory) {
        String owner = Objects.requireNonNull(configurationName, "configurationName");
        ClassLoader loader = Objects.requireNonNull(classLoader, "classLoader");
        String selected = requireLocation(location);
        Path base = Objects.requireNonNull(baseDirectory, "baseDirectory");
        if (selected.startsWith(CLASSPATH_PREFIX)) {
            return LogyardConfigurationSource.classpath(
                    loader,
                    selected.substring(CLASSPATH_PREFIX.length()),
                    base);
        }
        if (selected.startsWith(FILE_PREFIX)) {
            return LogyardConfigurationSource.file(Path.of(URI.create(selected)));
        }
        if (hasScheme(selected)) {
            throw new IllegalStateException(
                    owner + " configuration supports classpath: and filesystem locations, but was: " + selected);
        }
        Path path = Path.of(selected);
        return LogyardConfigurationSource.file(path.isAbsolute() ? path : base.resolve(path));
    }

    private static String requireLocation(String location) {
        String selected = Objects.requireNonNull(location, "location").trim();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("configuration location must not be blank");
        }
        return selected;
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
}
