package com.logyard4j.logyard.api.spi.config;

import org.junit.jupiter.api.Test;

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
}
