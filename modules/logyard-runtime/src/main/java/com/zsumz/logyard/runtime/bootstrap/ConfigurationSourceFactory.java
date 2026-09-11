package com.zsumz.logyard.runtime.bootstrap;

import java.nio.file.Path;
import java.util.Objects;

/** Creates validated source descriptors for filesystem, classpath, text, and built-in configuration. */
final class ConfigurationSourceFactory {
    private static final int MAX_DESCRIPTION_CHARS = 2_048;
    private static final String SAFE_DEFAULTS = """
            schema = 1

            [runtime]
            watch = false

            [delivery]
            mode = "async"
            capacity = 256

            [delivery.overflow]
            trace = "drop"
            debug = "drop"
            info = "drop"
            warn = "drop"
            error = "drop"

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
        String normalizedResource = resource(resource);
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
        String normalized = Objects.requireNonNull(value, "description").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("configuration source description must not be blank");
        }
        if (normalized.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("configuration source description exceeds " + MAX_DESCRIPTION_CHARS + " characters");
        }
        StringBuilder safe = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            safe.append(Character.isISOControl(character) ? '?' : character);
        }
        return safe.toString();
    }

    private static String resource(String value) {
        String normalized = Objects.requireNonNull(value, "resource").trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || normalized.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("classpath resource must be a non-blank forward-slash path");
        }
        return normalized;
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
