package com.zsumz.logyard.spring.boot.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.jul.LogyardHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

final class LogyardLoggingSystemTest {
    private LogyardLoggingSystem system;

    @AfterEach
    void cleanUp() {
        if (system != null) {
            system.cleanUp();
        }
    }

    @Test
    void handsEarlyLoggingToFinalConfigurationWithoutReplacingRuntimeIdentity() throws Exception {
        system = new LogyardLoggingSystem(getClass().getClassLoader());
        system.beforeInitialize();
        LogyardRuntime runtime = Logyard.runtime();
        LogyardLogger earlyLogger = runtime.logger("example.Service");
        assertTrue(earlyLogger.isInfoEnabled());
        assertFalse(earlyLogger.isDebugEnabled());

        Path source = writeConfig("debug");
        system.initialize(
                initialization(Map.of("logyard.config", source.toString())),
                null,
                null);

        assertSame(runtime, Logyard.runtime());
        assertSame(earlyLogger, runtime.logger("example.Service"));
        assertTrue(earlyLogger.isDebugEnabled());
        LoggerConfiguration inherited = system.getLoggerConfiguration("example.Service");
        assertNull(inherited.getConfiguredLevel());
        assertEquals(LogLevel.DEBUG, inherited.getEffectiveLevel());

        system.setLogLevel("example", LogLevel.ERROR);
        assertFalse(earlyLogger.isWarnEnabled());
        assertTrue(earlyLogger.isErrorEnabled());
        assertEquals(LogLevel.ERROR, system.getLoggerConfiguration("example").getConfiguredLevel());

        system.setLogLevel("example", null);
        assertTrue(earlyLogger.isDebugEnabled());
        assertTrue(system.getLoggerConfigurations().stream()
                .anyMatch(configuration -> configuration.getName().equals("example.Service")));

        system.cleanUp();
        system = null;
        assertTrue(Logyard.isInitialized());
    }

    @Test
    void restoresJulAndPreservesRuntimeIdentityAcrossLifecycleRestarts() throws Exception {
        long originalLogyardHandlers = logyardHandlerCount();
        system = new LogyardLoggingSystem(getClass().getClassLoader());
        system.beforeInitialize();
        LogyardRuntime runtime = Logyard.runtime();
        LogyardLogger logger = runtime.logger("restart.Service");

        assertEquals(1, logyardHandlerCount());
        system.initialize(initialization(Map.of("logyard.config", writeConfig("debug").toString())), null, null);
        system.cleanUp();
        assertEquals(originalLogyardHandlers, logyardHandlerCount());

        system.beforeInitialize();
        system.initialize(initialization(Map.of("logyard.config", writeConfig("error").toString())), null, null);

        assertSame(runtime, Logyard.runtime());
        assertSame(logger, runtime.logger("restart.Service"));
        assertFalse(logger.isWarnEnabled());
        assertTrue(logger.isErrorEnabled());
        assertEquals(1, logyardHandlerCount());

        system.cleanUp();
        assertEquals(originalLogyardHandlers, logyardHandlerCount());
    }

    @Test
    void rejectsConflictingLogyardAndBootConfigurationLocations() throws Exception {
        system = new LogyardLoggingSystem(getClass().getClassLoader());
        system.beforeInitialize();
        Path logyard = writeConfig("info");
        Path boot = writeConfig("error");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> system.initialize(
                        initialization(Map.of(
                                "logyard.config", logyard.toString(),
                                "logging.config", boot.toString())),
                        null,
                        null));

        assertTrue(failure.getMessage().contains("conflicting"));
        assertTrue(Logyard.isInitialized());
    }

    @Test
    void factoryCanBeDisabledAtTheEarlySystemPropertyBoundary() {
        String previous = System.getProperty("logyard.enabled");
        try {
            System.setProperty("logyard.enabled", "false");
            assertNull(new LogyardLoggingSystemFactory().getLoggingSystem(getClass().getClassLoader()));
        } finally {
            if (previous == null) {
                System.clearProperty("logyard.enabled");
            } else {
                System.setProperty("logyard.enabled", previous);
            }
        }
    }

    private static LoggingInitializationContext initialization(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return new LoggingInitializationContext(environment);
    }

    private static long logyardHandlerCount() {
        return Arrays.stream(LogManager.getLogManager().getLogger("").getHandlers())
                .filter(LogyardHandler.class::isInstance)
                .count();
    }

    private static Path writeConfig(String level) throws Exception {
        Path source = Files.createTempFile("logyard-spring-", ".toml");
        Files.writeString(source, """
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
                """.formatted(level), StandardCharsets.UTF_8);
        return source;
    }
}
