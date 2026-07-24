package org.junit.jupiter.api;

import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.function.ThrowingSupplier;

import java.util.Objects;
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
        if (condition) {
            throw new AssertionError("expected false");
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
        if (expected != actual) {
            throw new AssertionError("expected same instance");
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

    public static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw unequal(expected, actual);
        }
    }

    public static void assertNotEquals(Object unexpected, Object actual) {
        if (Objects.equals(unexpected, actual)) {
            throw new AssertionError("expected values to differ, but both were <" + actual + ">");
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

    private static AssertionError unequal(Object expected, Object actual) {
        return new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
}
