package com.logyard4j.spring.boot.internal.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class Slf4jProviderGuardTest {
    private static final String LOGYARD_PROVIDER = "com.logyard4j.slf4j.LogyardServiceProvider";

    @Test
    void acceptsLogyardAsTheSoleProvider() {
        assertDoesNotThrow(() -> Slf4jProviderGuard.validateProviders(List.of(LOGYARD_PROVIDER)));
    }

    @Test
    void givesExactRemediationForACompetingProvider() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> Slf4jProviderGuard.validateProviders(List.of(
                        LOGYARD_PROVIDER,
                        "ch.qos.logback.classic.spi.LogbackServiceProvider")));

        assertTrue(failure.getMessage().contains("ch.qos.logback.classic.spi.LogbackServiceProvider"));
        assertTrue(failure.getMessage().contains("exclude org.springframework.boot:spring-boot-starter-logging"));
        assertTrue(failure.getMessage().contains("remove other SLF4J providers"));
    }
}
