package com.logyard4j.core.failure;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ComponentInvocationBoundaryTest {
    @Test
    void reportsRecoverableErrorsWithoutEscaping() {
        AssertionError failure = new AssertionError("component failed");
        AtomicReference<Throwable> reported = new AtomicReference<>();

        boolean completed = ComponentInvocationBoundary.invoke(
                "test component",
                () -> {
                    throw failure;
                },
                (component, current) -> {
                    assertEquals("test component", component);
                    reported.set(current);
                });

        assertFalse(completed);
        assertSame(failure, reported.get());
    }

    @Test
    void convertsRecoverableErrorsForTransactionalCallers() {
        AssertionError failure = new AssertionError("provider failed");

        ComponentInvocationException converted = assertThrows(
                ComponentInvocationException.class,
                () -> ComponentInvocationBoundary.call("test provider", () -> {
                    throw failure;
                }));

        assertSame(failure, converted.getCause());
        assertEquals("test provider", converted.component());
    }

    @Test
    void preservesInterruptedStatus() {
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            assertFalse(ComponentInvocationBoundary.invoke(
                    "interrupting component",
                    () -> {
                        throw new InterruptedException("interrupted");
                    },
                    (component, failure) -> {
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rethrowsFatalJvmFailures() {
        LinkageError fatal = new LinkageError("fatal");

        LinkageError caught = assertThrows(
                LinkageError.class,
                () -> ComponentInvocationBoundary.invoke(
                        "fatal component",
                        () -> {
                            throw fatal;
                        },
                        (component, failure) -> {
                        }));

        assertSame(fatal, caught);
    }
}
