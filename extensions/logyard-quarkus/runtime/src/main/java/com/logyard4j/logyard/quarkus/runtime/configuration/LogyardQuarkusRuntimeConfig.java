package com.logyard4j.logyard.quarkus.runtime.configuration;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/** Runtime configuration for the Logyard Quarkus extension. */
@ConfigMapping(prefix = "quarkus.logyard")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface LogyardQuarkusRuntimeConfig {
    /**
     * Controls whether Quarkus installs the Logyard handler.
     *
     * @return {@code true} when capture is enabled
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Selects a filesystem path or {@code classpath:} resource.
     *
     * @return explicit framework configuration location
     */
    Optional<String> config();

    /**
     * Makes absence of explicit and conventional configuration an application startup failure.
     *
     * @return {@code true} when configuration is required
     */
    @WithDefault("false")
    boolean required();
}
