package com.zsumz.logyard.spring.boot.nativeimage;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class LogyardRuntimeHintsTest {
    @Test
    void includesConventionalAndNamedLogyardConfigurationResources() {
        RuntimeHints hints = new RuntimeHints();

        new LogyardRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertTrue(RuntimeHintsPredicates.resource().forResource("logyard.toml").test(hints));
        assertTrue(RuntimeHintsPredicates.resource().forResource("logging/logyard-prod.toml").test(hints));
    }
}
