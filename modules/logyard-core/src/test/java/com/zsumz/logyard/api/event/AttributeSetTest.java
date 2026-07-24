package com.zsumz.logyard.api.event;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        String nonDottedAuthorization = "x".repeat(300) + "authorization";
        String normalizedNonDotted = CaptureLimits.attributeKey(nonDottedAuthorization);
        assertEquals(CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS, normalizedNonDotted.length());
        assertTrue(normalizedNonDotted.endsWith("authorization"));
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

        char[] nonDottedBoundary = new char[400];
        java.util.Arrays.fill(nonDottedBoundary, 'x');
        nonDottedBoundary[109] = '\uD83D';
        nonDottedBoundary[110] = '\uDE80';
        nonDottedBoundary[271] = '\uD83D';
        nonDottedBoundary[272] = '\uDE80';
        "authorization".getChars(0, "authorization".length(), nonDottedBoundary, 387);
        String normalizedNonDotted = CaptureLimits.attributeKey(new String(nonDottedBoundary));
        assertValidUtf16(normalizedNonDotted);
        assertTrue(normalizedNonDotted.endsWith("authorization"));
    }

    @Test
    void performsBoundedValidationForHostileLongKeys() {
        String longKey = "request." + "x".repeat(2_000_000) + ".authorization";
        AttributeSet attributes = AttributeSet.of(longKey, "secret");

        assertEquals(1, attributes.size());
        assertTrue(attributes.keyAt(0).length() <= CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
        assertTrue(attributes.keyAt(0).endsWith(".authorization"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AttributeSet.of(" ".repeat(2_000_000), "rejected"));
    }

    @Test
    void disambiguatesBoundedHashCollisionsWithoutLosingEitherAttribute() {
        char[] firstCharacters = new char[5_000];
        java.util.Arrays.fill(firstCharacters, 'x');
        char[] secondCharacters = firstCharacters.clone();
        firstCharacters[2_501] = 'a';
        secondCharacters[2_501] = 'b';
        String first = new String(firstCharacters);
        String second = new String(secondCharacters);
        assertEquals(CaptureLimits.attributeKey(first), CaptureLimits.attributeKey(second));

        AttributeSet attributes = AttributeSet.builder()
                .put(first, "first")
                .put(second, "second")
                .build();

        assertEquals(2, attributes.size());
        assertNotEquals(attributes.keyAt(0), attributes.keyAt(1));
        assertEquals("first", attributes.valueAt(0));
        assertEquals("second", attributes.valueAt(1));
        assertTrue(attributes.keyAt(1).contains("~collision-2"));
    }

    @Test
    void disambiguatesCollisionBetweenLiteralAndNormalizedKeys() {
        String longKey = "x".repeat(5_000);
        String literalKey = CaptureLimits.attributeKey(longKey);

        AttributeSet attributes = AttributeSet.builder()
                .put(literalKey, "literal")
                .put(longKey, "first")
                .put(longKey, "updated")
                .build();

        assertEquals(2, attributes.size());
        assertEquals(literalKey, attributes.keyAt(0));
        assertEquals("literal", attributes.valueAt(0));
        assertTrue(attributes.keyAt(1).contains("~collision-2"));
        assertEquals("updated", attributes.valueAt(1));
    }

    @Test
    void typeQualifiesNonStringMapKeysAndPreservesCanonicalCollisions() {
        Map<Object, Object> source = new LinkedHashMap<>();
        source.put(1, "integer");
        source.put("1", "string");

        @SuppressWarnings("unchecked")
        Map<String, Object> captured = (Map<String, Object>) AttributeSet.of("map", source).get("map");

        assertEquals(2, captured.size());
        assertTrue(captured.containsValue("integer"));
        assertTrue(captured.containsValue("string"));
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
