package com.logyard4j.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.Logyard;
import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.runtime.management.LoggerLevel;
import com.logyard4j.runtime.management.LoggerLevelManagement;
import com.logyard4j.spring.boot.logging.LogyardLoggingSystem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

final class LogyardAutoConfigurationTest {
    private LogyardLoggingSystem system;

    @AfterEach
    void cleanUp() {
        try {
            if (system != null) {
                system.cleanUp();
            }
        } finally {
            Logyard.shutdown();
        }
    }

    @Test
    void exposesTheInstalledRuntimeWithoutTakingAnotherOwnershipLease() throws Exception {
        Path source = writeConfig();
        system = new LogyardLoggingSystem(getClass().getClassLoader());
        system.beforeInitialize();
        LogyardRuntime installed = Logyard.runtime();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test",
                    Map.of("logyard.config", source.toString())));
            system.initialize(new LoggingInitializationContext(context.getEnvironment()), null, null);
            context.register(
                    LogyardAutoConfiguration.class,
                    LogyardActuatorAutoConfiguration.class);
            context.refresh();

            assertSame(installed, context.getBean(LogyardRuntime.class));
            assertEquals(
                    LoggerLevel.INFO,
                    context.getBean(LoggerLevelManagement.class).getEffectiveLevel("example.Service"));
            assertTrue(context.getBean(LogyardRuntimeHealth.class).snapshot().ready());
            assertEquals(source.toString(), context.getBean(LogyardProperties.class).getConfig());
            HealthIndicator indicator = context.getBean("logyard", HealthIndicator.class);
            Health actuatorHealth = indicator.health();
            assertEquals(Status.UP, actuatorHealth.getStatus());
            assertEquals("HEALTHY", actuatorHealth.getDetails().get("status"));
            assertEquals(Status.UP, indicator.getHealth(true).getStatus());
        }

        assertTrue(Logyard.isInitialized());
        assertSame(installed, Logyard.runtime());
        assertTrue(installed.health().ready());
        LoggerLevelManagement.forRuntime(installed).setLevel("example.Service", LoggerLevel.DEBUG);
        assertTrue(installed.logger("example.Service").isDebugEnabled());
    }

    private static Path writeConfig() throws Exception {
        Path source = Files.createTempFile("logyard-spring-autoconfigure-", ".toml");
        Files.writeString(source, """
                schema = 1
                [runtime]
                internal_status = "off"
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                stream = "stderr"
                color = { mode = "never" }
                """, StandardCharsets.UTF_8);
        return source;
    }
}
