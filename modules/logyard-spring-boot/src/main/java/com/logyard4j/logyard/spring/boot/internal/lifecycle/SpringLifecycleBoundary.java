package com.logyard4j.logyard.spring.boot.internal.lifecycle;

import com.logyard4j.logyard.api.failure.FailureIsolation;

/** Recursion-free failure isolation for callbacks invoked by Spring's logging lifecycle. */
final class SpringLifecycleBoundary {
    private static final int MAX_MESSAGE_CHARACTERS = 1_024;

    private SpringLifecycleBoundary() {
    }

    static void invoke(String operation, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            System.err.println("Logyard Spring Boot " + operation + " failed: " + summary(failure));
        }
    }

    private static String summary(Throwable failure) {
        String message;
        try {
            message = failure.getMessage();
        } catch (Throwable messageFailure) {
            FailureIsolation.prepareForRecovery(messageFailure);
            message = "<unavailable>";
        }
        String summary = failure.getClass().getName() + (message == null ? "" : ": " + sanitize(message));
        return summary.length() <= MAX_MESSAGE_CHARACTERS
                ? summary
                : summary.substring(0, MAX_MESSAGE_CHARACTERS - 3) + "...";
    }

    private static String sanitize(String value) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_MESSAGE_CHARACTERS));
        for (int index = 0; index < value.length() && result.length() < MAX_MESSAGE_CHARACTERS; index++) {
            char character = value.charAt(index);
            result.append(Character.isISOControl(character) ? ' ' : character);
        }
        return result.toString();
    }

    @FunctionalInterface
    interface ThrowingAction {
        void run() throws Throwable;
    }
}
