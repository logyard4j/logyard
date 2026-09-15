package com.logyard4j.logyard.spring.boot.internal.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EarlyEnablementTest {
    @Test
    void parsesPaddedBooleanWithoutChangingTheDefault() {
        withProperty(" \tFaLsE\r\n", () -> assertFalse(EarlyEnablement.isEnabled()));
        withProperty(" \tTrUe\r\n", () -> assertTrue(EarlyEnablement.isEnabled()));
        withProperty(" \t\r\n", () -> assertTrue(EarlyEnablement.isEnabled()));
    }

    @Test
    void rejectsOversizedInvalidValueWithBoundedDiagnostic() {
        withProperty(" \t" + "invalid".repeat(20_000) + "\r\n", () -> {
            IllegalStateException error = assertThrows(IllegalStateException.class, EarlyEnablement::isEnabled);
            assertTrue(error.getMessage().contains("must be true or false"));
            assertTrue(error.getMessage().length() < 300);
        });
    }

    private static void withProperty(String value, Runnable check) {
        String previous = System.getProperty("logyard.enabled");
        System.setProperty("logyard.enabled", value);
        try {
            check.run();
        } finally {
            if (previous == null) {
                System.clearProperty("logyard.enabled");
            } else {
                System.setProperty("logyard.enabled", previous);
            }
        }
    }
}
