package com.zsumz.logyard.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ExceptionSnapshotTest {
    @Test
    void capturesDetachedCauseSuppressionAndFrames() {
        IllegalArgumentException cause = new IllegalArgumentException("root cause");
        cause.setStackTrace(new StackTraceElement[] {frame("Cause")});
        IllegalStateException failure = new IllegalStateException("top level", cause);
        failure.setStackTrace(new StackTraceElement[] {frame("Top")});
        failure.addSuppressed(new UnsupportedOperationException("suppressed"));

        ExceptionSnapshot snapshot = ExceptionSnapshot.capture(failure);
        failure.setStackTrace(new StackTraceElement[] {frame("Changed")});

        assertEquals(IllegalStateException.class.getName(), snapshot.type());
        assertEquals("top level", snapshot.message());
        assertEquals(frame("Top"), snapshot.frames().getFirst());
        assertEquals("root cause", snapshot.cause().message());
        assertEquals("suppressed", snapshot.suppressed().getFirst().message());
    }

    @Test
    void capturesAStableFallbackWhenTheMessageAccessorFails() {
        RuntimeException failure = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new IllegalStateException("unavailable");
            }
        };

        ExceptionSnapshot snapshot = ExceptionSnapshot.capture(failure);

        assertEquals("[message accessor failed: java.lang.IllegalStateException]", snapshot.message());
        assertTrue(snapshot.truncated());
    }

    private static StackTraceElement frame(String type) {
        return new StackTraceElement(type, "method", type + ".java", 7);
    }
}
