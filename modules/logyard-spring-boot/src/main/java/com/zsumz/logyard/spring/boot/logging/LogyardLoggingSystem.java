package com.zsumz.logyard.spring.boot.logging;

import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.management.LoggerLevel;
import com.zsumz.logyard.runtime.management.LoggerLevelManagement;
import com.zsumz.logyard.runtime.management.LoggerLevelSnapshot;
import com.zsumz.logyard.spring.boot.internal.configuration.SpringConfigurationResolver;
import com.zsumz.logyard.spring.boot.internal.lifecycle.FrameworkRuntimeLifecycle;
import com.zsumz.logyard.spring.boot.internal.provider.Slf4jProviderGuard;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;

/**
 * Spring Boot logging system backed by the process-wide Logyard runtime.
 *
 * <p>The early safe-default lease and the final environment-selected lease reconfigure one runtime
 * in place, preserving logger instances already created during framework startup.</p>
 */
public final class LogyardLoggingSystem extends LoggingSystem {
    private static final Set<LogLevel> SUPPORTED_LEVELS =
            Collections.unmodifiableSet(EnumSet.allOf(LogLevel.class));

    private final ClassLoader classLoader;
    private final FrameworkRuntimeLifecycle lifecycle = new FrameworkRuntimeLifecycle();

    /** Creates a logging system for the application class loader. */
    public LogyardLoggingSystem(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /** Installs safe defaults early enough to receive Spring Boot startup events. */
    @Override
    public void beforeInitialize() {
        Slf4jProviderGuard.requireSoleLogyardProvider(classLoader);
        lifecycle.startEarly();
    }

    /** Applies the final Spring environment configuration to the existing runtime identity. */
    @Override
    public void initialize(
            LoggingInitializationContext initializationContext,
            String configLocation,
            LogFile logFile) {
        Objects.requireNonNull(initializationContext, "initializationContext");
        boolean enabled = initializationContext.getEnvironment().getProperty("logyard.enabled", Boolean.class, true);
        if (!enabled) {
            throw new IllegalStateException(
                    "Logyard logging is selected before application properties are loaded; disable it with "
                            + "-Dlogyard.enabled=false or LOGYARD_ENABLED=false");
        }
        LogyardConfigurationSource source = SpringConfigurationResolver.resolve(
                classLoader,
                initializationContext.getEnvironment(),
                configLocation);
        lifecycle.configure(source);
    }

    /** Flushes pending events and releases this framework ownership lease. */
    @Override
    public void cleanUp() {
        lifecycle.close();
    }

    /** Returns an idempotent shutdown action for Spring Boot's logging lifecycle. */
    @Override
    public Runnable getShutdownHandler() {
        return this::cleanUp;
    }

    @Override
    public Set<LogLevel> getSupportedLogLevels() {
        return SUPPORTED_LEVELS;
    }

    /** Publishes or clears one hierarchical runtime level override. */
    @Override
    public void setLogLevel(String loggerName, LogLevel level) {
        levels().setLevel(normalizeLoggerName(loggerName), toLogyard(level));
    }

    /** Returns known runtime loggers plus explicitly configured operational overrides. */
    @Override
    public List<LoggerConfiguration> getLoggerConfigurations() {
        Map<String, LoggerLevelSnapshot> snapshots = levels().listLoggerLevels();
        List<LoggerConfiguration> configurations = new ArrayList<>(snapshots.size());
        snapshots.forEach((name, snapshot) -> configurations.add(toSpring(name, snapshot)));
        return List.copyOf(configurations);
    }

    /** Returns one logger configuration, including inherited effective level. */
    @Override
    public LoggerConfiguration getLoggerConfiguration(String loggerName) {
        String normalized = normalizeLoggerName(loggerName);
        return toSpring(normalized, levels().getLoggerLevel(normalized));
    }

    private LoggerLevelManagement levels() {
        return LoggerLevelManagement.forRuntime(lifecycle.runtime());
    }

    private static LoggerConfiguration toSpring(String name, LoggerLevelSnapshot snapshot) {
        return new LoggerConfiguration(
                name,
                toSpring(snapshot.configuredLevel()),
                toSpring(snapshot.effectiveLevel()));
    }

    private static LoggerLevel toLogyard(LogLevel level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case TRACE -> LoggerLevel.TRACE;
            case DEBUG -> LoggerLevel.DEBUG;
            case INFO -> LoggerLevel.INFO;
            case WARN -> LoggerLevel.WARN;
            case ERROR, FATAL -> LoggerLevel.ERROR;
            case OFF -> LoggerLevel.OFF;
        };
    }

    private static LogLevel toSpring(LoggerLevel level) {
        return level == null ? null : LogLevel.valueOf(level.name());
    }

    private static String normalizeLoggerName(String loggerName) {
        return loggerName == null || LoggingSystem.ROOT_LOGGER_NAME.equalsIgnoreCase(loggerName)
                ? LoggerLevelManagement.ROOT_LOGGER_NAME
                : loggerName;
    }
}
