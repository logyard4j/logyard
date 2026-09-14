package com.logyard4j.logyard.api.spi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderConfigurationTest {
    @Test
    void rejectsNormalizedKeyCollisions() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(" token", "first");
        values.put("token", "second");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ProviderConfiguration(values));

        assertTrue(failure.getMessage().contains("duplicate normalized"));
    }

    @Test
    void oversizedKeysHaveBoundedDiagnosticsAcrossConfigurationAndSpecifications() {
        String key = " " + "k".repeat(1_000_000) + " ";

        assertBoundedKeyFailure(() -> new ProviderConfiguration(Map.of(key, true)));
        assertBoundedKeyFailure(() -> ProviderConfigurationSpec.of(Set.of(key), Set.of()));
        assertBoundedKeyFailure(() -> ProviderConfigurationSpec.of(Set.of("valid"), Set.of(key)));
    }

    @Test
    void exactKeyLimitRemainsSupportedWithLargeWhitespacePadding() {
        String key = "k".repeat(ProviderConfiguration.MAX_KEY_CHARS);
        String padding = " \t\r\n".repeat(1_000);
        String padded = padding + key + padding;
        ProviderConfiguration configuration = new ProviderConfiguration(Map.of(padded, true));
        ProviderConfigurationSpec specification = ProviderConfigurationSpec.of(Set.of(padded), Set.of(padded));

        assertEquals(Map.of(key, true), configuration.values());
        assertEquals(Set.of(key), specification.allowedKeys());
        assertEquals(Set.of(key), specification.requiredKeys());
        specification.validate(configuration);
        assertBoundedKeyFailure(() -> new ProviderConfiguration(Map.of(padding + key + "k" + padding, true)));
    }

    @Test
    void specificationsNormalizeAndValuesRemainFiniteAndSecret() {
        ProviderConfigurationSpec spec = ProviderConfigurationSpec.of(Set.of(" token "), Set.of("token"));
        ProviderConfiguration first = new ProviderConfiguration(Map.of("token", "value", "retries", 3L));
        ProviderConfiguration second = new ProviderConfiguration(Map.of("retries", 3L, "token", "value"));

        spec.validate(new ProviderConfiguration(Map.of("token", "value")));

        assertEquals(Set.of("token"), spec.allowedKeys());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertFalse(first.toString().contains("value"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> ProviderConfigurationSpec.of(Set.of(" token", "token"), Set.of())).getMessage().contains("duplicate normalized"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderConfiguration(Map.of("ratio", Double.NaN))).getMessage().contains("must be finite"));
    }

    private static void assertBoundedKeyFailure(Executable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().length() <= 256, "oversized key diagnostics must remain bounded");
        assertTrue(failure.getMessage().contains(Integer.toString(ProviderConfiguration.MAX_KEY_CHARS)));
    }
}
