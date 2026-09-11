package org.junit.jupiter.api;

import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.function.ThrowingSupplier;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class Assertions {
    private Assertions() {
    }

    public static void assertTrue(boolean condition) {
        assertTrue(condition, "expected true");
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertTrue(boolean condition, Supplier<String> messageSupplier) {
        if (!condition) {
            throw new AssertionError(messageSupplier.get());
        }
    }

    public static void assertFalse(boolean condition) {
        assertFalse(condition, "expected false");
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertFalse(boolean condition, Supplier<String> messageSupplier) {
        if (condition) {
            throw new AssertionError(messageSupplier.get());
        }
    }

    public static void assertNull(Object actual) {
        assertNull(actual, "expected null but was <" + actual + ">");
    }

    public static void assertNull(Object actual, String message) {
        if (actual != null) {
            throw new AssertionError(message);
        }
    }

    public static void assertNotNull(Object actual) {
        if (actual == null) {
            throw new AssertionError("expected a non-null value");
        }
    }

    public static void assertSame(Object expected, Object actual) {
        assertSame(expected, actual, "expected same instance");
    }

    public static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message);
        }
    }

    public static void assertNotSame(Object unexpected, Object actual) {
        assertNotSame(unexpected, actual, "expected different instances");
    }

    public static void assertNotSame(Object unexpected, Object actual, String message) {
        if (unexpected == actual) {
            throw new AssertionError(message);
        }
    }

    public static <T> T assertInstanceOf(Class<T> expectedType, Object actual) {
        Objects.requireNonNull(expectedType, "expectedType");
        if (!expectedType.isInstance(actual)) {
            throw new AssertionError(
                    "expected instance of " + expectedType.getName()
                            + " but was "
                            + (actual == null ? "null" : actual.getClass().getName()));
        }
        return expectedType.cast(actual);
    }

    public static void assertEquals(long expected, long actual) {
        assertEquals(expected, actual, (String) null);
    }

    public static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw message == null ? unequal(expected, actual) : new AssertionError(message);
        }
    }

    public static void assertEquals(int expected, int actual) {
        assertEquals((long) expected, (long) actual);
    }

    public static void assertEquals(int expected, Integer actual) {
        if (actual == null || expected != actual) {
            throw unequal(expected, actual);
        }
    }

    public static void assertEquals(Integer expected, int actual) {
        if (expected == null || expected != actual) {
            throw unequal(expected, actual);
        }
    }

    public static void assertEquals(long expected, Long actual) {
        if (actual == null || expected != actual) {
            throw unequal(expected, actual);
        }
    }

    public static void assertEquals(Long expected, long actual) {
        if (expected == null || expected != actual) {
            throw unequal(expected, actual);
        }
    }

    public static void assertEquals(Long expected, Long actual) {
        assertEquals((Object) expected, (Object) actual);
    }

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, (String) null);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw message == null ? unequal(expected, actual) : new AssertionError(message);
        }
    }

    public static void assertArrayEquals(Object[] expected, Object[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw unequal(Arrays.toString(expected), Arrays.toString(actual));
        }
    }

    public static void assertEquals(Object expected, Object actual, Supplier<String> messageSupplier) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(messageSupplier.get());
        }
    }

    public static void assertNotEquals(Object unexpected, Object actual) {
        assertNotEquals(unexpected, actual, null);
    }

    public static void assertNotEquals(Object unexpected, Object actual, String message) {
        if (Objects.equals(unexpected, actual)) {
            throw new AssertionError(
                    message == null ? "expected values to differ, but both were <" + actual + ">" : message);
        }
    }

    public static void assertEquals(double expected, double actual, double delta) {
        if (!(Math.abs(expected - actual) <= delta)) {
            throw unequal(expected, actual);
        }
    }

    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {
        Objects.requireNonNull(expectedType, "expectedType");
        Objects.requireNonNull(executable, "executable");
        try {
            executable.execute();
        } catch (Throwable failure) {
            if (expectedType.isInstance(failure)) {
                return expectedType.cast(failure);
            }
            throw new AssertionError(
                    "expected " + expectedType.getName() + " but caught " + failure.getClass().getName(),
                    failure);
        }
        throw new AssertionError("expected " + expectedType.getName() + " to be thrown");
    }

    public static void assertDoesNotThrow(Executable executable) {
        Objects.requireNonNull(executable, "executable");
        try {
            executable.execute();
        } catch (Throwable failure) {
            throw new AssertionError("unexpected exception", failure);
        }
    }

    public static <T> T assertDoesNotThrow(ThrowingSupplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        try {
            return supplier.get();
        } catch (Throwable failure) {
            throw new AssertionError("unexpected exception", failure);
        }
    }

    public static void assertTimeoutPreemptively(Duration timeout, Executable executable) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(executable, "executable");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().name("logyard-fallback-timeout").unstarted(() -> {
            try {
                executable.execute();
            } catch (Throwable executionFailure) {
                failure.set(executionFailure);
            }
        });
        worker.start();

        boolean completed;
        try {
            completed = worker.join(timeout);
        } catch (InterruptedException interrupted) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new AssertionError("timeout assertion was interrupted", interrupted);
        }
        if (!completed) {
            worker.interrupt();
            throw new AssertionError("execution exceeded timeout of " + timeout);
        }
        if (failure.get() != null) {
            Assertions.<RuntimeException>throwUnchecked(failure.get());
        }
    }

    private static AssertionError unequal(Object expected, Object actual) {
        return new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
