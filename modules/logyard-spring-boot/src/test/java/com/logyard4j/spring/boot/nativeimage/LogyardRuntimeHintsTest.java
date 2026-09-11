package com.logyard4j.spring.boot.nativeimage;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LogyardRuntimeHintsTest {
    @Test
    void includesConventionalAndNamedLogyardConfigurationResources() {
        RuntimeHints hints = new RuntimeHints();

        new LogyardRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertTrue(RuntimeHintsPredicates.resource().forResource("logyard.toml").test(hints));
        assertTrue(RuntimeHintsPredicates.resource().forResource("logging/logyard-prod.toml").test(hints));
    }

    @Test
    void includesThePublishedContextProviderServiceContract() {
        RuntimeHints hints = new RuntimeHints();

        new LogyardRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertTrue(RuntimeHintsPredicates.resource()
                .forResource("META-INF/services/com.logyard4j.api.spi.context.ContextProvider")
                .test(hints));
        assertFalse(RuntimeHintsPredicates.resource()
                .forResource("META-INF/services/com.logyard4j.api.spi.context.CallerContextProvider")
                .test(hints));
    }
}
