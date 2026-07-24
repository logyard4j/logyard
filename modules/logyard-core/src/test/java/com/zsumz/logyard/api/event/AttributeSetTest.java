package com.zsumz.logyard.api.event;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AttributeSetTest {
    @Test
    void preservesInsertionOrderWhileReplacingValues() {
        AttributeSet attributes = AttributeSet.builder()
                .put("first", 1)
                .put("second", 2)
                .put("first", 3)
                .build();

        assertEquals(2, attributes.size());
        assertEquals("first", attributes.keyAt(0));
        assertEquals(3, attributes.valueAt(0));
        assertEquals("second", attributes.keyAt(1));
    }

    @Test
    void reservesTheLastBoundedSlotForTheTruncationMarkerWithoutEvaluatingDiscardedSuppliers() {
        AttributeSet.Builder builder = AttributeSet.builder(CaptureLimits.MAX_ATTRIBUTES);
        for (int index = 0; index < CaptureLimits.MAX_ATTRIBUTES - 1; index++) {
            builder.put("key." + index, index);
        }
        AtomicInteger evaluations = new AtomicInteger();

        builder.putSupplied("discarded", evaluations::incrementAndGet);
        AttributeSet attributes = builder.build();

        assertEquals(0, evaluations.get());
        assertEquals(CaptureLimits.MAX_ATTRIBUTES, attributes.size());
        assertEquals(true, attributes.get("logyard.attributes.truncated"));
    }

    @Test
    void boundsAttributeKeysWhilePreservingSecurityRelevantLeafNames() {
        String at255 = "x".repeat(255);
        String at256 = "x".repeat(256);
        String at257 = "x".repeat(257);

        assertSame(at255, CaptureLimits.attributeKey(at255));
        assertSame(at256, CaptureLimits.attributeKey(at256));
        assertEquals(256, CaptureLimits.attributeKey(at257).length());

        String authorization = "prefix.".repeat(50) + "authorization";
        String sessionToken = "prefix.".repeat(50) + "session.token";
        String normalizedAuthorization = CaptureLimits.attributeKey(authorization);
        String normalizedSessionToken = CaptureLimits.attributeKey(sessionToken);

        assertTrue(normalizedAuthorization.length() <= CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
        assertTrue(normalizedAuthorization.endsWith(".authorization"));
        assertTrue(normalizedSessionToken.length() <= CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
        assertTrue(normalizedSessionToken.endsWith(".token"));
    }

    @Test
    void longKeyNormalizationIsUnicodeSafeAndCollisionResistant() {
        String unicodeBoundary = "x".repeat(224) + "\uD83D\uDE80" + "middle.".repeat(30) + "authorization";
        String first = "same-prefix.".repeat(30) + "first-middle.authorization";
        String second = "same-prefix.".repeat(30) + "second-middle.authorization";

        String normalizedUnicode = CaptureLimits.attributeKey(unicodeBoundary);
        String normalizedFirst = CaptureLimits.attributeKey(first);
        String normalizedSecond = CaptureLimits.attributeKey(second);

        assertValidUtf16(normalizedUnicode);
        assertTrue(normalizedUnicode.length() <= CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
        assertTrue(normalizedUnicode.endsWith(".authorization"));
        assertNotEquals(normalizedFirst, normalizedSecond);
        assertTrue(normalizedFirst.endsWith(".authorization"));
        assertTrue(normalizedSecond.endsWith(".authorization"));
    }

    private static void assertValidUtf16(String value) {
        Set<Integer> codePoints = new HashSet<>();
        value.codePoints().forEach(codePoints::add);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                assertTrue(index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1)));
                index++;
            } else {
                assertTrue(!Character.isLowSurrogate(character));
            }
        }
        assertTrue(!codePoints.isEmpty());
    }
}
