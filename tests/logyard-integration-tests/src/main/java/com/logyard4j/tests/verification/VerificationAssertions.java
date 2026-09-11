package com.logyard4j.tests.verification;

import com.logyard4j.api.spi.context.ContextProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

final class VerificationAssertions {
    private VerificationAssertions() {
    }

    static void equal(Object expected, Object actual) {
        if (!Objects.deepEquals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static List<String> withDiscoveredContext(List<String> configured) {
        List<String> expected = new ArrayList<>();
        if (ServiceLoader.load(ContextProvider.class).findFirst().isPresent()) expected.add("logyard-context");
        expected.addAll(configured);
        return List.copyOf(expected);
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
