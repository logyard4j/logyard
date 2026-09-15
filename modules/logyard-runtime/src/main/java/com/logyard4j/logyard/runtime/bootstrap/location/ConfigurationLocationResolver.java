package com.logyard4j.logyard.runtime.bootstrap.location;

import com.logyard4j.logyard.api.annotation.InternalApi;
import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves the portable location syntax shared by framework configuration handoffs.
 *
 * @hidden
 */
@InternalApi
public final class ConfigurationLocationResolver {
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILE_PREFIX = "file:";
    private static final int MAX_FILE_LOCATION_CHARS = 32_768;
    private static final int MAX_CLASSPATH_RESOURCE_CHARS = 2_048;

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
        String selected = normalize(location);
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

    /** Normalizes a framework location before alias comparison or resolution.
     * @param location nonblank classpath, file URI, or filesystem location
     * @return bounded normalized location
     */
    public static String normalize(String location) {
        Objects.requireNonNull(location, "location");
        int start = 0;
        int end = location.length();
        while (start < end && location.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && location.charAt(end - 1) <= ' ') {
            end--;
        }
        if (start == end) {
            throw new IllegalArgumentException("configuration location must not be blank");
        }
        if (location.regionMatches(start, CLASSPATH_PREFIX, 0, CLASSPATH_PREFIX.length())) {
            int resourceStart = start + CLASSPATH_PREFIX.length();
            while (resourceStart < end && location.charAt(resourceStart) <= ' ') {
                resourceStart++;
            }
            while (resourceStart < end && location.charAt(resourceStart) == '/') {
                resourceStart++;
            }
            if (resourceStart == end) {
                throw new IllegalArgumentException("classpath resource must be a non-blank forward-slash path");
            }
            if (end - resourceStart > MAX_CLASSPATH_RESOURCE_CHARS) {
                throw new IllegalArgumentException(
                        "classpath resource exceeds " + MAX_CLASSPATH_RESOURCE_CHARS + " characters");
            }
            for (int index = resourceStart; index < end; index++) {
                if (location.charAt(index) == '\\') {
                    throw new IllegalArgumentException("classpath resource must be a non-blank forward-slash path");
                }
            }
            return CLASSPATH_PREFIX + location.substring(resourceStart, end);
        }
        if (end - start > MAX_FILE_LOCATION_CHARS) {
            throw new IllegalArgumentException("configuration location exceeds " + MAX_FILE_LOCATION_CHARS + " characters");
        }
        return location.substring(start, end);
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
