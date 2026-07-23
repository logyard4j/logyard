package com.zsumz.logyard.runtime.bootstrap;

import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Opaque configuration content with a stable description and deterministic base directory.
 *
 * <p>Applications create sources through the static factories. File sources can be watched;
 * classpath, text, and built-in sources can be re-read explicitly but have no watcher.</p>
 */
public final class LogyardConfigurationSource {
    private static final int MAX_DESCRIPTION_CHARS = 2_048;
    private static final String SAFE_DEFAULTS = """
            schema = 1

            [runtime]
            watch = false

            [delivery]
            mode = "async"
            capacity = 65536

            [delivery.overflow]
            trace = "drop"
            debug = "drop"
            info = "drop"
            warn = "stderr"
            error = "stderr"

            [loggers]
            root = { level = "info", outputs = ["console"] }

            [outputs.console]
            type = "console"
            stream = "stderr"
            color = { mode = "auto", theme = "ember" }
            """;

    private final String description;
    private final Path baseDirectory;
    private final Path watchPath;
    private final ContentReader reader;

    private LogyardConfigurationSource(
            String description,
            Path baseDirectory,
            Path watchPath,
            ContentReader reader) {
        this.description = description(description);
        this.baseDirectory = normalizeDirectory(baseDirectory);
        this.watchPath = watchPath == null ? null : watchPath.toAbsolutePath().normalize();
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /**
     * Creates a reloadable filesystem source.
     *
     * @param path configuration file path
     * @return file-backed source
     */
    public static LogyardConfigurationSource file(Path path) {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        Path base = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
        return new LogyardConfigurationSource(
                normalized.toString(),
                base,
                normalized,
                () -> readFile(normalized));
    }

    /**
     * Creates a classpath resource source.
     *
     * @param loader resource class loader
     * @param resource classpath resource without a leading slash
     * @param baseDirectory base for relative output paths
     * @return classpath-backed source
     */
    public static LogyardConfigurationSource classpath(
            ClassLoader loader,
            String resource,
            Path baseDirectory) {
        Objects.requireNonNull(loader, "loader");
        String normalized = resource(resource);
        return new LogyardConfigurationSource(
                "classpath:" + normalized,
                baseDirectory,
                null,
                () -> readClasspath(loader, normalized));
    }

    /**
     * Creates an immutable bounded-text source for tests and framework handoff.
     *
     * @param description safe diagnostic description
     * @param toml TOML configuration
     * @param baseDirectory base for relative output paths
     * @return in-memory source
     */
    public static LogyardConfigurationSource text(
            String description,
            String toml,
            Path baseDirectory) {
        byte[] content = boundedUtf8(toml);
        return new LogyardConfigurationSource(
                description,
                baseDirectory,
                null,
                () -> content.clone());
    }

    /**
     * Creates the built-in safe configuration: root INFO, stderr console, bounded asynchronous
     * nonblocking delivery, no file output, and no watcher.
     *
     * @return safe default source
     */
    public static LogyardConfigurationSource defaults() {
        return defaults(Path.of("."));
    }

    /**
     * Creates the built-in safe configuration with a caller-selected base directory.
     *
     * @param baseDirectory base for any future relative paths
     * @return safe default source
     */
    public static LogyardConfigurationSource defaults(Path baseDirectory) {
        return text("built-in safe defaults", SAFE_DEFAULTS, baseDirectory);
    }

    /**
     * Returns the stable source description used in diagnostics.
     *
     * @return source description
     */
    public String description() {
        return description;
    }

    ConfigurationSnapshot snapshot() throws IOException {
        return ConfigurationSnapshot.capture(
                description,
                baseDirectory,
                watchPath,
                reader.read());
    }

    Path watchPath() {
        return watchPath;
    }

    private static byte[] readFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Logyard configuration is not a regular file: " + path);
        }
        long declaredSize = Files.size(path);
        if (declaredSize > LogyardConfigLoader.MAX_CONFIG_BYTES) {
            throw new IOException("Logyard configuration exceeds " + LogyardConfigLoader.MAX_CONFIG_BYTES + " bytes: " + path);
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > LogyardConfigLoader.MAX_CONFIG_BYTES) {
            throw new IOException("Logyard configuration grew beyond " + LogyardConfigLoader.MAX_CONFIG_BYTES + " bytes while reading: " + path);
        }
        return bytes;
    }

    private static byte[] readClasspath(ClassLoader loader, String resource) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Logyard classpath configuration does not exist: classpath:" + resource);
            }
            byte[] bytes = stream.readNBytes(LogyardConfigLoader.MAX_CONFIG_BYTES + 1);
            if (bytes.length > LogyardConfigLoader.MAX_CONFIG_BYTES) {
                throw new IOException("Logyard classpath configuration exceeds " + LogyardConfigLoader.MAX_CONFIG_BYTES + " bytes: classpath:" + resource);
            }
            return bytes;
        }
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

    private static byte[] boundedUtf8(String value) {
        String text = Objects.requireNonNull(value, "toml");
        if (text.length() > LogyardConfigLoader.MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException("Logyard text configuration exceeds " + LogyardConfigLoader.MAX_CONFIG_BYTES + " bytes");
        }
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        if (content.length > LogyardConfigLoader.MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException("Logyard text configuration exceeds " + LogyardConfigLoader.MAX_CONFIG_BYTES + " bytes");
        }
        return content;
    }

    private static Path normalizeDirectory(Path directory) {
        return Objects.requireNonNull(directory, "baseDirectory").toAbsolutePath().normalize();
    }

    @FunctionalInterface
    private interface ContentReader {
        byte[] read() throws IOException;
    }
}
