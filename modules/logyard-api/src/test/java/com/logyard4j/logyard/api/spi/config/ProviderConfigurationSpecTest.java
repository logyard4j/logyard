package com.logyard4j.logyard.api.spi.config;

import org.junit.jupiter.api.Test;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ProviderConfigurationSpecTest {
    @Test
    void oversizedAllowedKeysAreRejectedBeforeNormalizationOrCopying() {
        assertThrows(IllegalArgumentException.class, () -> ProviderConfigurationSpec.of(new OversizedKeys(), Set.of()));
    }

    @Test
    void oversizedRequiredKeysAreRejectedBeforeNormalizationOrCopying() {
        assertThrows(IllegalArgumentException.class, () -> ProviderConfigurationSpec.of(Set.of(), new OversizedKeys()));
    }

    @Test
    void exactKeyLimitIsSupportedAndDetachedFromTheProvider() {
        Set<String> keys = new LinkedHashSet<>();
        for (int index = 0; index < ProviderConfiguration.MAX_ENTRIES; index++) keys.add("key" + index);
        ProviderConfigurationSpec specification = ProviderConfigurationSpec.of(keys, keys);
        keys.clear();

        assertEquals(ProviderConfiguration.MAX_ENTRIES, specification.allowedKeys().size());
        assertEquals(specification.allowedKeys(), specification.requiredKeys());
        assertThrows(UnsupportedOperationException.class, () -> specification.allowedKeys().clear());
        assertThrows(UnsupportedOperationException.class, () -> specification.requiredKeys().clear());
    }

    private static final class OversizedKeys extends AbstractSet<String> {
        @Override
        public Iterator<String> iterator() {
            throw new AssertionError("oversized provider keys must not be traversed");
        }

        @Override
        public int size() {
            return ProviderConfiguration.MAX_ENTRIES + 1;
        }
    }
}
