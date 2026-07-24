package com.zsumz.logyard.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.delivery.OverflowAction;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import com.zsumz.logyard.config.output.ConsoleOutputConfig;
import com.zsumz.logyard.config.output.JsonFileOutputConfig;

import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

final class LogyardConfigurationSourceTest {
    @Test
    void safeDefaultsAreBoundedNonblockingAndDoNotWatchOrWriteFiles() throws Exception {
        LogyardConfigurationSource source = LogyardConfigurationSource.defaults(Files.createTempDirectory("logyard-default-base-"));
        LogyardConfig config = source.snapshot().parse(Map.of());

        assertEquals("built-in safe defaults", source.description());
        assertNull(source.watchPath());
        assertFalse(config.runtime().watch());
        assertEquals(Level.INFO, config.rootLogger().level());
        assertTrue(config.delivery().asynchronous());
        assertEquals(16_384, config.delivery().capacity());
        assertEquals(OverflowAction.DROP, config.delivery().overflow().get(Level.INFO).action());
        assertEquals(OverflowAction.STDERR, config.delivery().overflow().get(Level.WARN).action());
        assertTrue(config.delivery().overflow().get(Level.WARN).after().isZero());
        assertEquals(1, config.outputs().size());
        ConsoleOutputConfig console = (ConsoleOutputConfig) config.outputs().get("console");
        assertEquals("stderr", console.stream());
        assertEquals("auto", console.color().mode());
        assertEquals("ember", console.color().theme());
    }

    @Test
    void classpathSourceReadsFromJarAndResolvesRelativePathsAgainstItsBase() throws Exception {
        Path directory = Files.createTempDirectory("logyard-classpath-source-");
        Path jar = directory.resolve("application.jar");
        writeJarResource(jar, "nested/logyard.toml", jsonFileConfig("logs/events.jsonl"));

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, null)) {
            Path base = directory.resolve("runtime-base");
            LogyardConfigurationSource source = LogyardConfigurationSource.classpath(loader, "/nested/logyard.toml", base);
            LogyardConfig config = source.snapshot().parse(Map.of());

            assertEquals("classpath:nested/logyard.toml", source.description());
            assertNull(source.watchPath());
            JsonFileOutputConfig output = (JsonFileOutputConfig) config.outputs().get("json");
            assertEquals(base.toAbsolutePath().normalize().resolve("logs/events.jsonl"), output.path());
        }
    }

    @Test
    void textSourceIsDigestStableAndExplicitlyReloadable() throws Exception {
        LogyardConfigurationSource source = LogyardConfigurationSource.text("framework:test", consoleConfig("debug"), Path.of("."));
        LogyardConfigurationSource equivalent = LogyardConfigurationSource.text("framework:test", consoleConfig("debug"), Path.of("."));

        assertTrue(source.snapshot().sameContent(source.snapshot()));
        assertEquals(source.identity(), equivalent.identity());
        try (RuntimeBundle bundle = LogyardBootstrap.start(source)) {
            assertEquals(source, bundle.configurationSource());
            assertNull(bundle.source());
            assertFalse(bundle.watchesConfiguration());
            assertTrue(bundle.runtime().logger("example.Logger").isDebugEnabled());
            assertEquals(ReloadResult.UNCHANGED, bundle.reloadNow());
        }
    }

    @Test
    void frameworkAcquisitionReconfiguresTheApplicationRuntimeWithoutReplacingIt() {
        LogyardConfigurationSource applicationSource = LogyardConfigurationSource.text("application:test", consoleConfig("info"), Path.of("."));
        LogyardConfigurationSource frameworkSource = LogyardConfigurationSource.text("framework:test", consoleConfig("debug"), Path.of("."));

        RuntimeBundle application = LogyardBootstrap.start(applicationSource);
        try {
            var logger = application.runtime().logger("example.Service");
            assertFalse(logger.isDebugEnabled());
            try (RuntimeBundle framework = LogyardBootstrap.acquire(RuntimeOwner.FRAMEWORK, frameworkSource)) {
                assertEquals(application.runtime(), framework.runtime());
                assertEquals(logger, framework.runtime().logger("example.Service"));
                assertTrue(logger.isDebugEnabled());
                application.close();
                assertTrue(framework.active());
            }
        } finally {
            application.close();
        }
    }

    @Test
    void textSourceRejectsOversizedContentBeforeBootstrap() {
        String oversized = "x".repeat(LogyardConfigLoader.MAX_CONFIG_BYTES + 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> LogyardConfigurationSource.text("oversized", oversized, Path.of(".")));
    }

    private static void writeJarResource(Path jar, String name, String content) throws Exception {
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream output = new JarOutputStream(file)) {
            output.putNextEntry(new JarEntry(name));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static String consoleConfig(String level) {
        return """
                schema = 1
                [runtime]
                watch = true
                internal_status = "off"
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "%s", outputs = ["console"] }
                [outputs.console]
                type = "console"
                stream = "stderr"
                color = { mode = "never" }
                """.formatted(level);
    }

    private static String jsonFileConfig(String relativePath) {
        return """
                schema = 1
                [runtime]
                watch = false
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "info", outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "%s"
                append = true
                buffer = "4KiB"
                flush = "0s"
                """.formatted(relativePath);
    }
}
