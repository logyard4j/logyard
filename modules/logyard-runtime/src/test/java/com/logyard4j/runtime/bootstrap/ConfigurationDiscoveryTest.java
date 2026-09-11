package com.logyard4j.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ConfigurationDiscoveryTest {
    @Test
    void explicitPropertyWinsEveryImplicitSource() throws Exception {
        Path directory = directoryWithConventionalFile();
        Path propertyFile = write(directory.resolve("property.toml"));
        Path environmentFile = write(directory.resolve("environment.toml"));
        LogyardConfigurationSource framework = LogyardConfigurationSource.text("framework:test", config(), directory);

        try (URLClassLoader loader = classpathWithConfig()) {
            LogyardConfigurationSource resolved = ConfigurationDiscovery.resolve(
                    propertyFile.toString(),
                    null,
                    Map.of(ConfigurationDiscovery.ENVIRONMENT_VARIABLE, environmentFile.toString()),
                    loader,
                    directory,
                    framework);

            assertEquals(propertyFile.toAbsolutePath().normalize().toString(), resolved.description());
        }
    }

    @Test
    void environmentWinsFrameworkAndConventionalSources() throws Exception {
        Path directory = directoryWithConventionalFile();
        Path environmentFile = write(directory.resolve("environment.toml"));
        LogyardConfigurationSource framework = LogyardConfigurationSource.text("framework:test", config(), directory);

        try (URLClassLoader loader = classpathWithConfig()) {
            LogyardConfigurationSource resolved = ConfigurationDiscovery.resolve(
                    null,
                    null,
                    Map.of(ConfigurationDiscovery.ENVIRONMENT_VARIABLE, environmentFile.toString()),
                    loader,
                    directory,
                    framework);

            assertEquals(environmentFile.toAbsolutePath().normalize().toString(), resolved.description());
        }
    }

    @Test
    void frameworkWinsClasspathAndWorkingDirectory() throws Exception {
        Path directory = directoryWithConventionalFile();
        LogyardConfigurationSource framework = LogyardConfigurationSource.text("framework:test", config(), directory);

        try (URLClassLoader loader = classpathWithConfig()) {
            LogyardConfigurationSource resolved = ConfigurationDiscovery.resolve(null, null, Map.of(), loader, directory, framework);
            assertEquals("framework:test", resolved.description());
        }
    }

    @Test
    void classpathWinsWorkingDirectory() throws Exception {
        Path directory = directoryWithConventionalFile();
        try (URLClassLoader loader = classpathWithConfig()) {
            LogyardConfigurationSource resolved = ConfigurationDiscovery.resolve(null, null, Map.of(), loader, directory, null);
            assertEquals("classpath:logyard.toml", resolved.description());
        }
    }

    @Test
    void workingDirectoryWinsSafeDefaults() throws Exception {
        Path directory = directoryWithConventionalFile();
        try (URLClassLoader loader = emptyClasspath()) {
            LogyardConfigurationSource resolved = ConfigurationDiscovery.resolve(null, null, Map.of(), loader, directory, null);
            assertEquals(directory.resolve("logyard.toml").toAbsolutePath().normalize().toString(), resolved.description());
        }
    }

    @Test
    void noConfigurationUsesSafeDefaultsUnlessStrictDiscoveryIsEnabled() throws Exception {
        Path directory = Files.createTempDirectory("logyard-discovery-empty-");
        try (URLClassLoader loader = emptyClasspath()) {
            assertEquals(
                    "built-in safe defaults",
                    ConfigurationDiscovery.resolve(null, null, Map.of(), loader, directory, null).description());
            assertThrows(
                    IllegalStateException.class,
                    () -> ConfigurationDiscovery.resolve(null, "true", Map.of(), loader, directory, null));
            assertThrows(
                    IllegalStateException.class,
                    () -> ConfigurationDiscovery.resolve(
                            null,
                            null,
                            Map.of(),
                            loader,
                            directory,
                            null,
                            true));
            assertThrows(
                    IllegalStateException.class,
                    () -> ConfigurationDiscovery.resolve(
                            null,
                            null,
                            Map.of(ConfigurationDiscovery.REQUIRED_ENVIRONMENT_VARIABLE, "yes"),
                            loader,
                            directory,
                            null));
        }
    }

    @Test
    void explicitMissingLocationsFailWithoutFallback() throws Exception {
        Path directory = directoryWithConventionalFile();
        try (URLClassLoader loader = classpathWithConfig()) {
            assertThrows(
                    IllegalStateException.class,
                    () -> ConfigurationDiscovery.resolve("missing.toml", null, Map.of(), loader, directory, null));
            assertThrows(
                    IllegalStateException.class,
                    () -> ConfigurationDiscovery.resolve("classpath:missing.toml", null, Map.of(), loader, directory, null));
        }
    }

    private static URLClassLoader classpathWithConfig() throws Exception {
        Path directory = Files.createTempDirectory("logyard-discovery-classpath-");
        Files.writeString(directory.resolve("logyard.toml"), config(), StandardCharsets.UTF_8);
        return new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()}, null);
    }

    private static URLClassLoader emptyClasspath() {
        return new URLClassLoader(new java.net.URL[0], null);
    }

    private static Path directoryWithConventionalFile() throws Exception {
        Path directory = Files.createTempDirectory("logyard-discovery-working-");
        write(directory.resolve("logyard.toml"));
        return directory;
    }

    private static Path write(Path path) throws Exception {
        Files.writeString(path, config(), StandardCharsets.UTF_8);
        return path;
    }

    private static String config() {
        return """
                schema = 1
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                color = { mode = "never" }
                """;
    }
}
