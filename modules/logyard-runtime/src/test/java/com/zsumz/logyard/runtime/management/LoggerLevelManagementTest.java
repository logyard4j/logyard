package com.zsumz.logyard.runtime.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.bootstrap.RuntimeOwner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
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
            LoggerLevelSnapshot serviceSnapshot = levels.listLoggerLevels().get("com.acme.Service");
            assertNull(serviceSnapshot.configuredLevel());
            assertEquals(LoggerLevel.DEBUG, serviceSnapshot.effectiveLevel());
            assertEquals(LoggerLevelOrigin.RUNTIME_OVERRIDE, serviceSnapshot.origin());

            LoggerLevelSnapshot overrideSnapshot = levels.listLoggerLevels().get("com.acme");
            assertEquals(LoggerLevel.DEBUG, overrideSnapshot.configuredLevel());
            assertEquals(LoggerLevel.DEBUG, overrideSnapshot.runtimeOverride());
            assertEquals(LoggerLevelOrigin.RUNTIME_OVERRIDE, overrideSnapshot.origin());

            LoggerLevelSnapshot rootSnapshot = levels.listLoggerLevels().get(LoggerLevelManagement.ROOT_LOGGER_NAME);
            assertEquals(LoggerLevel.INFO, rootSnapshot.configuredLevel());
            assertEquals(LoggerLevel.INFO, rootSnapshot.baseConfiguredLevel());
            assertEquals(LoggerLevelOrigin.BASE_CONFIGURATION, rootSnapshot.origin());
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
    void exposesExactBaseRulesBeforeLoggerInstantiationAndRestoresThemAfterOverrides() {
        LogyardConfigurationSource source = LogyardConfigurationSource.text(
                "configured-levels",
                configuredCategory(),
                Path.of("."));

        try (RuntimeBundle bundle = LogyardBootstrap.start(source)) {
            LoggerLevelManagement levels = LoggerLevelManagement.forRuntime(bundle.runtime());

            assertEquals(
                    Map.of(
                            LoggerLevelManagement.ROOT_LOGGER_NAME, LoggerLevel.INFO,
                            "com.acme.orders", LoggerLevel.DEBUG),
                    levels.listBaseConfiguredLevels());
            LoggerLevelSnapshot base = levels.getLoggerLevel("com.acme.orders");
            assertEquals(LoggerLevel.DEBUG, base.configuredLevel());
            assertEquals(LoggerLevel.DEBUG, base.baseConfiguredLevel());
            assertNull(base.runtimeOverride());
            assertEquals(LoggerLevelOrigin.BASE_CONFIGURATION, base.origin());

            LoggerLevelSnapshot inherited = levels.getLoggerLevel("com.acme.orders.Repository");
            assertNull(inherited.configuredLevel());
            assertEquals(LoggerLevel.DEBUG, inherited.effectiveLevel());
            assertEquals(LoggerLevelOrigin.INHERITED, inherited.origin());

            LoggerLevelSnapshot explicitlyInherited = levels.listLoggerLevels().get("com.acme.inherited");
            assertNull(explicitlyInherited.configuredLevel());
            assertNull(explicitlyInherited.baseConfiguredLevel());
            assertEquals(LoggerLevel.INFO, explicitlyInherited.effectiveLevel());
            assertEquals(LoggerLevelOrigin.INHERITED, explicitlyInherited.origin());

            levels.setLevel("com.acme.orders", LoggerLevel.ERROR);
            LoggerLevelSnapshot overridden = levels.getLoggerLevel("com.acme.orders");
            assertEquals(LoggerLevel.ERROR, overridden.configuredLevel());
            assertEquals(LoggerLevel.DEBUG, overridden.baseConfiguredLevel());
            assertEquals(LoggerLevel.ERROR, overridden.runtimeOverride());
            assertEquals(LoggerLevelOrigin.RUNTIME_OVERRIDE, overridden.origin());

            levels.clearLevel("com.acme.orders");
            assertEquals(base, levels.getLoggerLevel("com.acme.orders"));
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

    @Test
    void listsTenThousandLoggerRulesFromOneLinearSnapshot() {
        Map<String, RouteDefinition> rules = new LinkedHashMap<>();
        for (int index = 0; index < 10_000; index++) {
            rules.put("com.example.service." + index, new RouteDefinition(Level.DEBUG, null, null));
        }
        EventSink sink = ignored -> { };
        RuntimePlan plan = new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture"), List.of()),
                rules,
                Map.of("capture", sink),
                Map.of());

        try (DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan)) {
            Map<String, LoggerLevelSnapshot> levels = LoggerLevelManagement.forRuntime(runtime).listLoggerLevels();

            assertEquals(10_001, levels.size());
            assertEquals(LoggerLevel.DEBUG, levels.get("com.example.service.9999").effectiveLevel());
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

    private static String configuredCategory() {
        return """
                schema = 1
                [runtime]
                internal_status = "off"
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "info", outputs = ["console"] }
                "com.acme.orders" = { level = "debug" }
                "com.acme.inherited" = { outputs = ["console"] }
                [outputs.console]
                type = "console"
                stream = "stderr"
                color = { mode = "never" }
                """;
    }
}
