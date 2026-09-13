package com.logyard4j.logyard.api.context;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.event.SystemAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the snapshot-restoring scope semantics of the native logging context. */
final class LogContextScopeTest {
    @AfterEach
    void contextIsUnboundAfterEveryTest() {
        assertTrue(LogContext.current().isEmpty(), "a test must not leak scoped context onto the runner thread");
    }

    @Test
    void currentIsTheSharedEmptySetWithoutAScope() {
        assertSame(AttributeSet.EMPTY, LogContext.current());
    }

    @Test
    void nestedScopesMergeAndTheInnermostWins() {
        AttributeSet outerValues = AttributeSet.builder(2)
                .put("tenant", "north")
                .put("stage", "outer")
                .build();
        ContextScope outer = LogContext.push(outerValues);
        try (outer) {
            assertEquals("north", LogContext.current().get("tenant"));
            ContextScope inner = LogContext.push("stage", "inner");
            try (inner) {
                assertEquals("inner", LogContext.current().get("stage"));
                assertEquals("north", LogContext.current().get("tenant"), "an inner scope keeps outer keys");
            }
            assertEquals("outer", LogContext.current().get("stage"), "closing restores the outer snapshot");
        }
        assertNull(LogContext.current().get("tenant"));
    }

    @Test
    void outOfOrderCloseKeepsInnerActiveAndSkipsClosedAncestors() {
        ContextScope guard = LogContext.push(AttributeSet.EMPTY);
        try (guard) {
            ContextScope outer = LogContext.push("stage", "outer");
            ContextScope inner = LogContext.push("stage", "inner");

            outer.close();
            assertEquals("inner", LogContext.current().get("stage"), "the inner scope remains active");
            inner.close();
            assertNull(LogContext.current().get("stage"), "closed outer scopes cannot be resurrected");
        }
    }

    @Test
    void closeIsIdempotent() {
        ContextScope guard = LogContext.push("stage", "guard");
        try (guard) {
            ContextScope scope = LogContext.push("stage", "scoped");
            scope.close();
            scope.close();
            assertEquals("guard", LogContext.current().get("stage"), "a repeated close must not restore twice");
        }
    }

    @Test
    void closingOnAForeignThreadIsANoOp() throws InterruptedException {
        ContextScope scope = LogContext.push("tenant", "north");
        try (scope) {
            AttributeSet[] observed = new AttributeSet[1];
            Thread foreign = new Thread(() -> {
                scope.close();
                observed[0] = LogContext.current();
            });
            foreign.start();
            foreign.join();

            assertTrue(observed[0].isEmpty(), "a foreign close must not touch the closing thread's context");
            assertEquals("north", LogContext.current().get("tenant"), "a foreign close must not unbind the owner");
        }
        assertTrue(LogContext.current().isEmpty(), "the owner can still close a scope a foreign thread touched");
    }

    @Test
    void contextIsNotInheritedByStartedThreads() throws InterruptedException {
        ContextScope scope = LogContext.push("tenant", "north");
        try (scope) {
            AttributeSet[] observed = new AttributeSet[1];
            Thread child = new Thread(() -> observed[0] = LogContext.current());
            child.start();
            child.join();
            assertSame(AttributeSet.EMPTY, observed[0]);
        }
    }

    @Test
    void reservedKeysFailAtPushTime() {
        assertThrows(IllegalArgumentException.class, () -> LogContext.push("logyard.tenant", "north"));
        assertThrows(IllegalArgumentException.class, () -> LogContext.push("LOGYARD.tenant", "north"));
        assertTrue(LogContext.current().isEmpty(), "a rejected push must not bind anything");
    }

    @Test
    void oversizedContextTruncatesInsteadOfThrowing() {
        int oversized = CaptureLimits.MAX_ATTRIBUTES * 2;
        AttributeSet.Builder builder = AttributeSet.builder(oversized);
        for (int index = 0; index < oversized; index++) {
            builder.put("key." + index, index);
        }
        ContextScope scope = LogContext.push(builder.build());
        try (scope) {
            AttributeSet current = LogContext.current();
            assertTrue(current.size() <= CaptureLimits.MAX_ATTRIBUTES, "context capture stays bounded");
            assertEquals(true, current.get(SystemAttributes.ATTRIBUTES_TRUNCATED));
        }
    }
}
