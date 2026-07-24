package com.zsumz.logyard.runtime.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.bootstrap.RuntimeOwner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LoggerLevelManagementTest {
    @Test
    void managesHierarchicalLevelsAndPreservesThemAcrossReload() throws Exception {
        Path directory = Files.createTempDirectory("logyard-level-management-");
        Path source = directory.resolve("logyard.toml");
        Files.writeString(source, config("info"), StandardCharsets.UTF_8);

        try (RuntimeBundle bundle = LogyardBootstrap.start(source)) {
            LoggerLevelManagement levels = LoggerLevelManagement.forRuntime(bundle.runtime());
            LogyardLogger service = bundle.runtime().logger("com.acme.Service");
            LogyardLogger noisy = bundle.runtime().logger("com.acme.noisy.Client");

            assertEquals(LoggerLevel.INFO, levels.getEffectiveLevel("com.acme.Service"));
            assertTrue(levels.listConfiguredLevels().isEmpty());

            levels.setLevel("com.acme", LoggerLevel.DEBUG);
            levels.setLevel("com.acme.noisy", LoggerLevel.OFF);
            assertEquals(LoggerLevel.DEBUG, levels.getEffectiveLevel("com.acme.Service"));
            assertEquals(LoggerLevel.OFF, levels.getEffectiveLevel("com.acme.noisy.Client"));
            assertTrue(service.isDebugEnabled());
            assertFalse(noisy.isErrorEnabled());
            assertEquals(
                    Map.of("com.acme", LoggerLevel.DEBUG, "com.acme.noisy", LoggerLevel.OFF),
                    levels.listConfiguredLevels());
            assertEquals(
                    new LoggerLevelSnapshot(null, LoggerLevel.DEBUG),
                    levels.listLoggerLevels().get("com.acme.Service"));
            assertEquals(
                    new LoggerLevelSnapshot(LoggerLevel.DEBUG, LoggerLevel.DEBUG),
                    levels.listLoggerLevels().get("com.acme"));
            assertEquals(
                    new LoggerLevelSnapshot(null, LoggerLevel.INFO),
                    levels.listLoggerLevels().get(LoggerLevelManagement.ROOT_LOGGER_NAME));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> levels.listConfiguredLevels().put("other", LoggerLevel.ERROR));

            Files.writeString(source, config("error"), StandardCharsets.UTF_8);
            assertEquals(ReloadResult.APPLIED, bundle.reloadNow());
            assertTrue(service.isDebugEnabled());
            assertEquals(LoggerLevel.DEBUG, levels.getEffectiveLevel("com.acme.Service"));

            levels.setLevel("com.acme", null);
            assertFalse(service.isWarnEnabled());
            assertTrue(service.isErrorEnabled());
            levels.clearAllOverrides();
            assertTrue(levels.listConfiguredLevels().isEmpty());
        }
    }

    @Test
    void rootOverrideAppliesToEveryLogger() throws Exception {
        Path source = Files.createTempFile("logyard-root-level-", ".toml");
        Files.writeString(source, config("error"), StandardCharsets.UTF_8);

        try (RuntimeBundle bundle = LogyardBootstrap.start(source)) {
            LoggerLevelManagement levels = LoggerLevelManagement.forRuntime(bundle.runtime());
            levels.setLevel("root", LoggerLevel.TRACE);

            assertEquals(LoggerLevel.TRACE, levels.getEffectiveLevel("anything"));
            assertTrue(bundle.runtime().logger("anything").isTraceEnabled());
            assertEquals(Map.of(LoggerLevelManagement.ROOT_LOGGER_NAME, LoggerLevel.TRACE), levels.listConfiguredLevels());
        }
    }

    @Test
    void frameworkSourceHandoffPreservesOperationalOverrides() {
        LogyardConfigurationSource applicationSource = LogyardConfigurationSource.text("application-levels", config("info"), Path.of("."));
        LogyardConfigurationSource frameworkSource = LogyardConfigurationSource.text("framework-levels", config("error"), Path.of("."));

        try (RuntimeBundle application = LogyardBootstrap.start(applicationSource)) {
            LoggerLevelManagement levels = LoggerLevelManagement.forRuntime(application.runtime());
            LogyardLogger logger = application.runtime().logger("com.acme.Service");
            levels.setLevel("com.acme", LoggerLevel.DEBUG);

            try (RuntimeBundle framework = LogyardBootstrap.acquire(RuntimeOwner.FRAMEWORK, frameworkSource)) {
                assertEquals(application.runtime(), framework.runtime());
                assertEquals(LoggerLevel.DEBUG, levels.getEffectiveLevel("com.acme.Service"));
                assertTrue(logger.isDebugEnabled());
            }
        }
    }

    private static String config(String level) {
        return """
                schema = 1
                [runtime]
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
}
