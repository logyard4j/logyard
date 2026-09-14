package com.logyard4j.logyard.api.event;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AttributeLookupTest {
    @Test
    void presenceDistinguishesCapturedNullFromAnAbsentKey() {
        AttributeSet attributes = AttributeSet.of("nullable", null);

        assertTrue(attributes.containsKey("nullable"));
        assertFalse(attributes.containsKey("absent"));
        assertNull(attributes.get("nullable"));
        assertNull(attributes.get("absent"));
        assertFalse(attributes.containsKey(null));
        assertFalse(AttributeSet.EMPTY.containsKey("nullable"));
    }

    @Test
    void lookupUsesExactCapturedKeysWithoutRenormalizingTheQuery() {
        String original = "x".repeat(CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS + 1);
        AttributeSet attributes = AttributeSet.builder().put(original, 1).put("Tenant", 2).build();
        String captured = attributes.keyAt(0);

        assertTrue(attributes.containsKey(captured));
        assertEquals(1, attributes.get(captured));
        assertFalse(attributes.containsKey(original));
        assertNull(attributes.get(original));
        assertTrue(attributes.containsKey("Tenant"));
        assertFalse(attributes.containsKey("tenant"));
        assertFalse(attributes.containsKey(" Tenant "));
    }

    @Test
    void lookupReflectsRightHandReplacementWithoutMutatingEarlierSets() {
        AttributeSet original = AttributeSet.of("tenant", "north");
        AttributeSet merged = original.mergedWith(AttributeSet.of("tenant", null));

        assertTrue(merged.containsKey("tenant"));
        assertNull(merged.get("tenant"));
        assertEquals("north", original.get("tenant"));
    }

    @Test
    void mapCopyPreservesNullsAndOrderWithoutExposingTheSnapshot() {
        AttributeSet attributes = AttributeSet.builder().put("nullable", null).put("tenant", "north").build();
        Map<String, Object> copy = attributes.toMap();

        assertEquals(List.of("nullable", "tenant"), List.copyOf(copy.keySet()));
        assertTrue(copy.containsKey("nullable"));
        assertNull(copy.get("nullable"));
        copy.clear();
        assertTrue(attributes.containsKey("nullable"));
        assertEquals("north", attributes.get("tenant"));
    }
}
