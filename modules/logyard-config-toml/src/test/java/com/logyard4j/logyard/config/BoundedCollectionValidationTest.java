package com.logyard4j.logyard.config;

import com.logyard4j.logyard.config.encoding.JsonAttributeTransformConfig;
import com.logyard4j.logyard.config.encoding.JsonProfileConfig;
import com.logyard4j.logyard.config.loading.overlay.ConfigOverlays;
import com.logyard4j.logyard.config.loading.overlay.OverrideEntry;
import com.logyard4j.logyard.config.runtime.ContextConfig;
import com.logyard4j.logyard.config.runtime.ResourceConfig;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class BoundedCollectionValidationTest {
    @Test
    void rejectsOversizedCollectionsBeforeTraversingThem() {
        assertOversized(() -> new ContextConfig(
                false,
                oversized(ContextConfig.MAX_ALLOWLIST_ENTRIES + 1),
                List.of(),
                List.of()));
        assertOversized(() -> new ResourceConfig(
                Map.of(),
                oversized(ResourceConfig.MAX_ATTRIBUTES + 1),
                List.of()));
        assertOversized(() -> new JsonAttributeTransformConfig(
                "nested",
                "attributes.",
                oversized(129),
                List.of(),
                Map.of()));
        assertOversized(() -> new JsonAttributeTransformConfig(
                "nested",
                "attributes.",
                List.of(),
                List.of(),
                oversizedMap(129)));
        assertOversized(() -> new JsonProfileConfig(
                "custom",
                "logyard",
                Map.of(),
                oversized(JsonProfileConfig.FIELDS.size() + 1),
                JsonAttributeTransformConfig.nested()));
        assertOversized(() -> new JsonProfileConfig(
                "custom",
                "logyard",
                oversizedMap(JsonProfileConfig.FIELDS.size() + 1),
                List.of(),
                JsonAttributeTransformConfig.nested()));
        assertOversized(() -> new ConfigOverlays(
                null,
                BoundedCollectionValidationTest.<OverrideEntry>oversized(
                        ConfigOverlays.MAX_OVERRIDES + 1)));
    }

    private static void assertOversized(Executable constructor) {
        assertThrows(IllegalArgumentException.class, constructor);
    }

    private static <T> List<T> oversized(int size) {
        return new AbstractList<>() {
            @Override
            public T get(int index) {
                throw new AssertionError("oversized list was traversed");
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    private static <K, V> Map<K, V> oversizedMap(int size) {
        return new AbstractMap<>() {
            @Override
            public AbstractSet<Entry<K, V>> entrySet() {
                throw new AssertionError("oversized map was traversed");
            }

            @Override
            public int size() {
                return size;
            }
        };
    }
}
