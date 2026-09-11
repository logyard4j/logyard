package com.logyard4j.spring.boot.autoconfigure;

import com.logyard4j.api.Logyard;
import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.runtime.management.LoggerLevelManagement;
import com.logyard4j.spring.boot.nativeimage.LogyardRuntimeHints;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/** Exposes the logging system's existing runtime and management views as Spring beans. */
@AutoConfiguration
@EnableConfigurationProperties(LogyardProperties.class)
@ConditionalOnProperty(prefix = "logyard", name = "enabled", havingValue = "true", matchIfMissing = true)
@ImportRuntimeHints(LogyardRuntimeHints.class)
public class LogyardAutoConfiguration {
    /** Exposes the installed runtime; its logging-system lease owns shutdown. */
    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean(LogyardRuntime.class)
    public LogyardRuntime logyardRuntime() {
        LogyardRuntime runtime = Logyard.runtimeOrNull();
        if (runtime == null) {
            throw new IllegalStateException(
                    "LogyardAutoConfiguration requires LogyardLoggingSystem to initialize the runtime first");
        }
        return runtime;
    }

    /** Exposes framework-neutral dynamic logger-level management. */
    @Bean
    @ConditionalOnMissingBean(LoggerLevelManagement.class)
    public LoggerLevelManagement logyardLoggerLevels(LogyardRuntime runtime) {
        return LoggerLevelManagement.forRuntime(runtime);
    }

    /** Exposes a live runtime health view without duplicating lifecycle ownership. */
    @Bean
    @ConditionalOnMissingBean(LogyardRuntimeHealth.class)
    public LogyardRuntimeHealth logyardRuntimeHealth(LogyardRuntime runtime) {
        return new LogyardRuntimeHealth(runtime);
    }
}
