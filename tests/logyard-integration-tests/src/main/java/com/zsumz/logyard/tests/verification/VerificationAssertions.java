package com.zsumz.logyard.tests.verification;

import java.util.Objects;

final class VerificationAssertions {
    private VerificationAssertions() {
    }

    static void equal(Object expected, Object actual) {
        if (!Objects.deepEquals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void expect(Class<? extends Throwable> expected, CheckedAction action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw new AssertionError("expected " + expected.getName() + " but got " + failure, failure);
        }
        throw new AssertionError("expected " + expected.getName() + " but no exception was thrown");
    }

    @FunctionalInterface
    interface CheckedAction {
        void run() throws Exception;
    }
}
