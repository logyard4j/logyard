package com.logyard4j.logyard.runtime.bootstrap;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigurationWatchPortabilityTest {
    @Test
    void nonDefaultFilesystemSourceDoesNotRequireWatchSupportWhenWatchingIsDisabled() throws Exception {
        try (FileSystem filesystem = zipFilesystem();
                RuntimeBundle bundle = start(filesystem, false)) {
            assertFalse(bundle.watchesConfiguration());
            assertTrue(bundle.runtime().logger("example.Service").isInfoEnabled());
        }
    }

    @Test
    void unsupportedWatchFilesystemFailsWithAnExplicitConfigurationBoundary() throws Exception {
        try (FileSystem filesystem = zipFilesystem()) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> start(filesystem, true));

            assertTrue(failure.getMessage().contains("configuration watch"));
            assertTrue(hasMessage(failure, "does not support watching"));
        }
    }

    private static RuntimeBundle start(FileSystem filesystem, boolean watch) throws Exception {
        Path source = filesystem.getPath("/logyard.toml");
        Files.writeString(source, config(watch), StandardCharsets.UTF_8);
        return LogyardBootstrap.start(source);
    }

    private static FileSystem zipFilesystem() throws Exception {
        Path archive = Files.createTempDirectory("logyard-zip-config-").resolve("configuration.zip");
        return FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"));
    }

    private static boolean hasMessage(Throwable failure, String expected) {
        Throwable current = failure;
        while (current != null) {
            if (String.valueOf(current.getMessage()).contains(expected)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String config(boolean watch) {
        return """
                schema = 1
                [runtime]
                watch = %s
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                stream = "stderr"
                color = { mode = "never" }
                """.formatted(watch);
    }
}
