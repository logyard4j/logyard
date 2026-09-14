package com.logyard4j.logyard.config.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigNamesTest {
    @Test
    void oversizedNamesProduceBoundedDiagnostics() {
        String oversized = " " + "x".repeat(1_000_000) + " ";

        assertBoundedFailure(() -> ConfigNames.component(oversized, "output name"));
        assertBoundedFailure(() -> ConfigNames.provider(oversized));
    }

    @Test
    void exactLimitsRemainSupportedWithLargeWhitespacePadding() {
        String component = "A" + "b".repeat(ConfigNames.MAX_NAME_CHARS - 1);
        String provider = "P" + "r".repeat(ConfigNames.MAX_NAME_CHARS - 1);
        String padding = " \t\r\n".repeat(1_000);

        assertEquals(component, ConfigNames.component(padding + component + padding, "output name"));
        assertEquals(
                provider.toLowerCase(java.util.Locale.ROOT),
                ConfigNames.provider(padding + provider + padding));
    }

    private static void assertBoundedFailure(Executable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().length() <= 128, "oversized name diagnostics must remain bounded");
        assertTrue(failure.getMessage().contains(Integer.toString(ConfigNames.MAX_NAME_CHARS)));
    }
}
