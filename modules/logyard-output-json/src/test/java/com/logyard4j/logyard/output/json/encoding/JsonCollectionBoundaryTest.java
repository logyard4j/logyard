package com.logyard4j.logyard.output.json.encoding;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class JsonCollectionBoundaryTest {
    @Test
    void rejectsOversizedCollectionsBeforeTraversingThem() {
        assertOversized(() -> new JsonAttributeTransform(
                JsonAttributeTransform.Mode.NESTED,
                "attributes.",
                List.of(),
                List.of(),
                oversizedMap(129)));
        assertOversized(() -> JsonProfile.custom(
                "custom",
                "logyard",
                oversizedMap(JsonProfile.FIELDS.size() + 1),
                List.of(),
                JsonAttributeTransform.nested()));
        assertOversized(() -> JsonProfile.custom(
                "custom",
                "logyard",
                Map.of(),
                oversized(JsonProfile.FIELDS.size() + 1),
                JsonAttributeTransform.nested()));
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
