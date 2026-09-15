package com.logyard4j.logyard.runtime.bootstrap;

import com.logyard4j.logyard.runtime.reload.ConfigurationSnapshot;

import java.io.IOException;
import java.nio.file.Path;

/** Opaque configuration content with stable diagnostics, identity, and optional file-watch path. */
public final class LogyardConfigurationSource {
    private final ConfigurationSourceDescriptor descriptor;

    private LogyardConfigurationSource(ConfigurationSourceDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    /**
     * Creates a reloadable filesystem source.
     * @param path configuration file path
     * @return filesystem configuration source
     */
    public static LogyardConfigurationSource file(Path path) {
        return new LogyardConfigurationSource(ConfigurationSourceFactory.file(path));
    }

    /**
     * Creates a classpath resource source with an explicit base directory for relative output paths.
     * @param loader class loader used to resolve the resource
     * @param resource classpath resource name, at most 2048 characters after leading slashes are removed
     * @param baseDirectory base directory for relative output paths
     * @return classpath configuration source
     */
    public static LogyardConfigurationSource classpath(ClassLoader loader, String resource, Path baseDirectory) {
        return new LogyardConfigurationSource(ConfigurationSourceFactory.classpath(loader, resource, baseDirectory));
    }

    /**
     * Creates an immutable bounded-text source for tests and framework handoff.
     * @param description source description for diagnostics, at most 2048 characters after trimming
     * @param toml TOML configuration content
     * @param baseDirectory base directory for relative output paths
     * @return text configuration source
     */
    public static LogyardConfigurationSource text(String description, String toml, Path baseDirectory) {
        return new LogyardConfigurationSource(ConfigurationSourceFactory.text(description, toml, baseDirectory));
    }

    /**
     * Creates the built-in safe configuration with the current directory as its base.
     * @return safe default configuration source
     */
    public static LogyardConfigurationSource defaults() {
        return defaults(Path.of("."));
    }

    /**
     * Creates the built-in safe configuration with a caller-selected base directory.
     * @param baseDirectory base directory for relative output paths
     * @return safe default configuration source
     */
    public static LogyardConfigurationSource defaults(Path baseDirectory) {
        return new LogyardConfigurationSource(ConfigurationSourceFactory.defaults(baseDirectory));
    }

    /**
     * Returns the stable source description used in diagnostics.
     * @return stable source description
     */
    public String description() {
        return descriptor.description();
    }

    ConfigurationSnapshot snapshot() throws IOException {
        return descriptor.snapshot();
    }

    Path watchPath() {
        return descriptor.watchPath();
    }

    Object identity() {
        return descriptor.identity();
    }
}
