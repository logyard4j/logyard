package com.zsumz.logyard.core.processing;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import org.junit.jupiter.api.Test;

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
    void evaluatesSharedCapturedContainersAtEachPath() {
        Map<String, String> shared = Map.of("token", "secret", "name", "shared");
        LogEvent original = event(AttributeSet.builder()
                .put("public", shared)
                .put("private", shared)
                .build());
        assertSame(original.attributes().get("public"), original.attributes().get("private"));

        LogEvent redacted = new RedactionProcessor(List.of("private.token")).process(original);

        assertEquals("secret", ((Map<?, ?>) redacted.attributes().get("public")).get("token"));
        assertEquals("[REDACTED]", ((Map<?, ?>) redacted.attributes().get("private")).get("token"));
    }

    private static LogEvent event(AttributeSet attributes) {
        return new LogEvent(
                1L,
                1_000_000L,
                Level.INFO,
                "test.Logger",
                null,
                "message",
                null,
                attributes,
                null,
                7L,
                "test");
    }
}
