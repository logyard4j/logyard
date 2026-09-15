package com.logyard4j.logyard.quarkus.runtime.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class QuarkusConfigurationResolverTest {
    @Test
    void rejectsOversizedLocationBeforeFrameworkHandoff() {
        assertThrows(IllegalArgumentException.class,
                () -> QuarkusConfigurationResolver.resolve(config(" x".repeat(16_385))));
    }

    @Test
    void normalizesLongClasspathSlashPrefixes() {
        LogyardConfigurationSource source = QuarkusConfigurationResolver.resolve(
                config(" \tclasspath:" + "/".repeat(50_000) + "config/logyard.toml\r\n"));

        assertEquals("classpath:config/logyard.toml", source.description());
    }

    private static LogyardQuarkusRuntimeConfig config(String location) {
        return new LogyardQuarkusRuntimeConfig() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Optional<String> config() {
                return Optional.of(location);
            }

            @Override
            public boolean required() {
                return false;
            }
        };
    }
}
