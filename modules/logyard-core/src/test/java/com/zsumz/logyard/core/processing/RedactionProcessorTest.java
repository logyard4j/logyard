package com.zsumz.logyard.core.processing;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RedactionProcessorTest {
    @Test
    void redactsSensitiveLeafNamesAfterLongKeyNormalization() {
        String authorization = "namespace.".repeat(40) + "authorization";
        String sessionToken = "namespace.".repeat(40) + "session.token";
        AttributeSet attributes = AttributeSet.builder()
                .put(authorization, "bearer-secret")
                .put(sessionToken, "session-secret")
                .build();

        LogEvent redacted = new RedactionProcessor(List.of("authorization", "*.token")).process(event(attributes));

        assertEquals("[REDACTED]", redacted.attributes().valueAt(0));
        assertEquals("[REDACTED]", redacted.attributes().valueAt(1));
        assertTrue(redacted.attributes().keyAt(0).endsWith(".authorization"));
        assertTrue(redacted.attributes().keyAt(1).endsWith(".token"));
    }

    @Test
    void redactsLongNonDottedSuffixesCaseInsensitively() {
        String authorization = "x".repeat(300) + "AuThOrIzAtIoN";
        String nestedAuthorization = "y".repeat(300) + "authorization";
        AttributeSet attributes = AttributeSet.builder()
                .put(authorization, "bearer-secret")
                .put("request", Map.of(nestedAuthorization, "nested-secret"))
                .build();

        LogEvent redacted = new RedactionProcessor(List.of("*authorization")).process(event(attributes));

        assertEquals("[REDACTED]", redacted.attributes().valueAt(0));
        assertTrue(redacted.attributes().keyAt(0).endsWith("AuThOrIzAtIoN"));
        assertEquals(
                "[REDACTED]",
                ((Map<?, ?>) redacted.attributes().get("request")).values().iterator().next());
    }

    @Test
    void redactsAUnicodeSafeLongNonDottedSuffix() {
        char[] keyCharacters = new char[400];
        Arrays.fill(keyCharacters, 'x');
        keyCharacters[109] = '\uD83D';
        keyCharacters[110] = '\uDE80';
        keyCharacters[271] = '\uD83D';
        keyCharacters[272] = '\uDE80';
        "authorization".getChars(0, "authorization".length(), keyCharacters, 387);
        AttributeSet attributes = AttributeSet.of(new String(keyCharacters), "secret");

        LogEvent redacted = new RedactionProcessor(List.of("*authorization")).process(event(attributes));

        assertEquals("[REDACTED]", redacted.attributes().valueAt(0));
        assertTrue(redacted.attributes().keyAt(0).endsWith("authorization"));
    }

    @Test
    void distinctLongPathsWithTheSamePrefixAndLeafRemainDistinct() {
        String shared = "namespace.".repeat(40);
        AttributeSet attributes = AttributeSet.builder()
                .put(shared + "first-middle.authorization", "first")
                .put(shared + "second-middle.authorization", "second")
                .build();

        assertEquals(2, attributes.size());
        assertNotEquals(attributes.keyAt(0), attributes.keyAt(1));
        assertTrue(attributes.keyAt(0).length() <= CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
        assertTrue(attributes.keyAt(1).length() <= CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
    }

    @Test
    void redactsNestedMapAndListValuesByFullPathAndLeaf() {
        AttributeSet attributes = AttributeSet.builder()
                .put("request", Map.of(
                        "users", List.of(Map.of(
                                "access_token", "token-secret",
                                "display_name", "Ada")),
                        "profile", Map.of(
                                "password", "password-secret",
                                "locale", "en-US")))
                .build();

        LogEvent redacted = new RedactionProcessor(List.of(
                "request.users[0].access_token",
                "password")).process(event(attributes));

        Map<?, ?> request = (Map<?, ?>) redacted.attributes().get("request");
        Map<?, ?> user = (Map<?, ?>) ((List<?>) request.get("users")).getFirst();
        Map<?, ?> profile = (Map<?, ?>) request.get("profile");
        assertEquals("[REDACTED]", user.get("access_token"));
        assertEquals("Ada", user.get("display_name"));
        assertEquals("[REDACTED]", profile.get("password"));
        assertEquals("en-US", profile.get("locale"));
    }

    @Test
    void preservesUnchangedEventsAndContainersByIdentity() {
        LogEvent original = event(AttributeSet.builder()
                .put("request", Map.of("profile", Map.of("locale", "en-US")))
                .build());
        Object originalRequest = original.attributes().get("request");

        LogEvent result = new RedactionProcessor(List.of("password")).process(original);

        assertSame(original, result);
        assertSame(originalRequest, result.attributes().get("request"));
    }

    @Test
    void redactsTheExpandedCopyAndLeavesSharedMarkersOpaque() {
        Map<String, String> shared = Map.of("token", "secret", "name", "shared");
        LogEvent original = event(AttributeSet.builder()
                .put("public", shared)
                .put("private", shared)
                .build());
        assertEquals("[shared reference]", original.attributes().get("private"));

        LogEvent redacted = new RedactionProcessor(List.of("public.token")).process(original);

        assertEquals("[REDACTED]", ((Map<?, ?>) redacted.attributes().get("public")).get("token"));
        assertEquals("[shared reference]", redacted.attributes().get("private"));
    }

    @Test
    void redactsTerminalSegmentsOfNestedDottedKeysCaseInsensitively() {
        String longAuthorization = "namespace.".repeat(40) + "AuThOrIzAtIoN";
        AttributeSet attributes = AttributeSet.builder()
                .put("request", Map.of(
                        "request.authorization", "dotted-secret",
                        longAuthorization, "long-secret",
                        "metadata.token[primary]", "bracket-secret",
                        "display.name", "Ada"))
                .build();

        LogEvent redacted = new RedactionProcessor(
                List.of("authorization", "token[primary]")).process(event(attributes));

        Map<?, ?> request = (Map<?, ?>) redacted.attributes().get("request");
        assertEquals("[REDACTED]", request.get("request.authorization"));
        assertEquals("[REDACTED]", request.entrySet().stream()
                .filter(entry -> entry.getKey().toString().endsWith(".AuThOrIzAtIoN"))
                .findFirst()
                .orElseThrow()
                .getValue());
        assertEquals("[REDACTED]", request.get("metadata.token[primary]"));
        assertEquals("Ada", request.get("display.name"));
    }

    @Test
    void findsALateMatchWithoutReusingTheRemainingCaptureBudgetOrDoubleChargingThePrefix() {
        Object[] arguments = new Object[31];
        for (int index = 0; index < arguments.length; index++) {
            arguments[index] = java.util.Collections.nCopies(128, "x");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        for (int index = 0; index < 20; index++) {
            request.put("ordinary." + index, index);
        }
        request.put("authorization", "late-secret");
        LogEvent original = event(arguments, AttributeSet.of("request", request));

        LogEvent redacted = new RedactionProcessor(List.of("authorization")).process(original);

        @SuppressWarnings("unchecked")
        Map<String, Object> captured = (Map<String, Object>) redacted.attributes().get("request");
        assertEquals(21, captured.size());
        assertEquals(0, captured.get("ordinary.0"));
        assertEquals("[REDACTED]", captured.get("authorization"));
    }

    @Test
    void reportsDefensiveTraversalExhaustion() {
        Map<String, Object> oversized = new LinkedHashMap<>();
        for (int index = 0; index <= CaptureLimits.MAX_EVENT_ENTRIES; index++) {
            oversized.put("key." + index, index);
        }
        StructuredValueRedactor redactor = new StructuredValueRedactor((path, leaf) -> false, "[REDACTED]");

        StructuredValueRedactor.Result result = redactor.redactChildren(oversized, "request");

        assertEquals("[REDACTED]", result.value());
        assertTrue(result.truncated());
    }

    private static LogEvent event(AttributeSet attributes) {
        return event(null, attributes);
    }

    private static LogEvent event(Object[] arguments, AttributeSet attributes) {
        return new LogEvent(
                1L,
                1_000_000L,
                Level.INFO,
                "test.Logger",
                null,
                "message",
                arguments,
                attributes,
                null,
                7L,
                "test");
    }
}
