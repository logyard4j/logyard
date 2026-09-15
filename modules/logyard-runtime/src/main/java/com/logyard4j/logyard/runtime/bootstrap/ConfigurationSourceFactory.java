package com.logyard4j.logyard.runtime.bootstrap;

import java.nio.file.Path;
import java.util.Objects;

/** Creates validated source descriptors for filesystem, classpath, text, and built-in configuration. */
final class ConfigurationSourceFactory {
    private static final int MAX_DESCRIPTION_CHARS = 2_048;
    private static final int MAX_RESOURCE_CHARS = 2_048;
    private static final String SAFE_DEFAULTS = """
            schema = 1

            [runtime]
            watch = false

            [delivery]
            mode = "async"
            capacity = 256

            [loggers]
            root = { level = "info", outputs = ["console"] }

            [outputs.console]
            type = "console"
            stream = "stderr"
            color = { mode = "auto", theme = "ember" }
            """;

    private ConfigurationSourceFactory() {
    }

    static ConfigurationSourceDescriptor file(Path path) {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        Path baseDirectory = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
        return new ConfigurationSourceDescriptor(
                normalized.toString(),
                baseDirectory,
                normalized,
                normalized,
                () -> ConfigurationSourceReader.readFile(normalized));
    }

    static ConfigurationSourceDescriptor classpath(ClassLoader loader, String resource, Path baseDirectory) {
        ClassLoader sourceLoader = Objects.requireNonNull(loader, "loader");
        String normalizedResource = resource(resource, 0);
        Path normalizedBaseDirectory = directory(baseDirectory);
        return new ConfigurationSourceDescriptor(
                "classpath:" + normalizedResource,
                normalizedBaseDirectory,
                null,
                new ClasspathSourceIdentity(sourceLoader, normalizedResource, normalizedBaseDirectory),
                () -> ConfigurationSourceReader.readClasspath(sourceLoader, normalizedResource));
    }

    static ConfigurationSourceDescriptor text(String description, String toml, Path baseDirectory) {
        String normalizedDescription = description(description);
        Path normalizedBaseDirectory = directory(baseDirectory);
        byte[] content = ConfigurationSourceReader.encodeText(toml);
        return new ConfigurationSourceDescriptor(
                normalizedDescription,
                normalizedBaseDirectory,
                null,
                new TextSourceIdentity(normalizedDescription, normalizedBaseDirectory),
                () -> content.clone());
    }

    static ConfigurationSourceDescriptor defaults(Path baseDirectory) {
        return text("built-in safe defaults", SAFE_DEFAULTS, baseDirectory);
    }

    private static String description(String value) {
        Objects.requireNonNull(value, "description");
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) <= ' ') {
            end--;
        }
        int length = end - start;
        if (length == 0) {
            throw new IllegalArgumentException("configuration source description must not be blank");
        }
        if (length > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("configuration source description exceeds " + MAX_DESCRIPTION_CHARS + " characters");
        }
        StringBuilder safe = new StringBuilder(length);
        for (int index = start; index < end; index++) {
            char character = value.charAt(index);
            safe.append(Character.isISOControl(character) ? '?' : character);
        }
        return safe.toString();
    }

    static String resource(String value, int offset) {
        Objects.requireNonNull(value, "resource");
        int start = offset;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) <= ' ') {
            end--;
        }
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        if (start == end) {
            throw new IllegalArgumentException("classpath resource must be a non-blank forward-slash path");
        }
        if (end - start > MAX_RESOURCE_CHARS) {
            throw new IllegalArgumentException("classpath resource exceeds " + MAX_RESOURCE_CHARS + " characters");
        }
        for (int index = start; index < end; index++) {
            if (value.charAt(index) == '\\') {
                throw new IllegalArgumentException("classpath resource must be a non-blank forward-slash path");
            }
        }
        return value.substring(start, end);
    }

    private static Path directory(Path value) {
        return Objects.requireNonNull(value, "baseDirectory").toAbsolutePath().normalize();
    }

    private record TextSourceIdentity(String description, Path baseDirectory) {
    }

    private static final class ClasspathSourceIdentity {
        private final ClassLoader loader;
        private final String resource;
        private final Path baseDirectory;

        private ClasspathSourceIdentity(ClassLoader loader, String resource, Path baseDirectory) {
            this.loader = loader;
            this.resource = resource;
            this.baseDirectory = baseDirectory;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ClasspathSourceIdentity identity
                    && loader == identity.loader
                    && resource.equals(identity.resource)
                    && baseDirectory.equals(identity.baseDirectory);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * System.identityHashCode(loader) + resource.hashCode()) + baseDirectory.hashCode();
        }
    }
}
