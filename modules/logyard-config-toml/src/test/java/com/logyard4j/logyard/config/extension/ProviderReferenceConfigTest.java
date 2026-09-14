package com.logyard4j.logyard.config.extension;

import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderReferenceConfigTest {
    @Test
    void oversizedImplementationNamesProduceBoundedDiagnostics() {
        String oversized = " " + "x".repeat(1_000_000) + " ";
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> reference(oversized));

        assertTrue(
                failure.getMessage().length() <= 128,
                "oversized implementation diagnostics must remain bounded");
        assertTrue(failure.getMessage().contains(
                Integer.toString(ProviderReferenceConfig.MAX_IMPLEMENTATION_CHARS)));
    }

    @Test
    void exactImplementationLimitRemainsSupportedWithLargeWhitespacePadding() {
        String implementation = "a." + "B".repeat(ProviderReferenceConfig.MAX_IMPLEMENTATION_CHARS - 2);
        String padding = " \t\r\n".repeat(1_000);

        assertEquals(implementation, reference(padding + implementation + padding).implementation());
        assertEquals("com.example.Provider", reference(" com.example.Provider ").implementation());
    }

    private static ProviderReferenceConfig reference(String implementation) {
        return new ProviderReferenceConfig("provider", implementation, ProviderConfiguration.EMPTY);
    }
}
