package com.logyard4j.core.failure;

import com.logyard4j.api.failure.FailureIsolation;
import com.logyard4j.core.diagnostics.EmergencyText;
import com.logyard4j.core.diagnostics.EmergencyReporter;

import java.util.Objects;

/**
 * The single recovery policy for third-party component and lifecycle callbacks.
 *
 * <p>Only fatal JVM failures escape unchanged. Recoverable failures preserve interruption and
 * are either reported without escaping or converted to a runtime exception for transactional
 * assembly paths.</p>
 */
public final class ComponentInvocationBoundary {
    private static final int MAX_COMPONENT_CHARS = 256;
    private static final int MAX_FAILURE_CHARS = 2_048;

    private ComponentInvocationBoundary() {
    }

    /**
     * Invokes an action and reports a recoverable failure without allowing it to escape.
     *
     * @return {@code true} when the action completed normally
     */
    public static boolean invoke(
            String component,
            ThrowingAction action,
            ComponentFailureReporter reporter) {
        String label = label(component);
        Objects.requireNonNull(action, "action");
        try {
            action.run();
            return true;
        } catch (Throwable failure) {
            report(label, failure, reporter);
            return false;
        }
    }

    /**
     * Invokes a value-producing callback and converts a recoverable failure to the runtime's
     * ordinary exception channel.
     */
    public static <T> T call(String component, ThrowingSupplier<T> supplier) {
        String label = label(component);
        Objects.requireNonNull(supplier, "supplier");
        try {
            return supplier.get();
        } catch (Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            throw new ComponentInvocationException(label, failure);
        }
    }

    /** Reports a failure already caught by a long-lived component loop. */
    public static void report(
            String component,
            Throwable failure,
            ComponentFailureReporter reporter) {
        String label = label(component);
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(reporter, "reporter");
        FailureIsolation.prepareForRecovery(failure);
        try {
            reporter.report(label, failure);
        } catch (Throwable reportingFailure) {
            FailureIsolation.prepareForRecovery(reportingFailure);
            EmergencyReporter.STDERR.report(
                    "Logyard component failure reporter failed for '" + label + "': primary="
                            + EmergencyText.failureSummary(failure, MAX_FAILURE_CHARS)
                            + ", reporter="
                            + EmergencyText.failureSummary(reportingFailure, MAX_FAILURE_CHARS));
        }
    }

    /** Converts an already classified recoverable failure into the ordinary exception channel. */
    public static ComponentInvocationException exception(String component, Throwable failure) {
        String label = label(component);
        FailureIsolation.prepareForRecovery(Objects.requireNonNull(failure, "failure"));
        return new ComponentInvocationException(label, failure);
    }

    private static String label(String component) {
        String normalized = Objects.requireNonNull(component, "component").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("component must not be blank");
        }
        return EmergencyText.sanitize(normalized, MAX_COMPONENT_CHARS);
    }
}
