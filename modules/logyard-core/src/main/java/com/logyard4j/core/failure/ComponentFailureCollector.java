package com.logyard4j.core.failure;

import java.util.Objects;

/** Collects recoverable component failures while allowing sibling operations to continue. */
public final class ComponentFailureCollector implements ComponentFailureReporter {
    private Throwable first;

    @Override
    public void report(String component, Throwable failure) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(failure, "failure");
        if (first == null) {
            first = failure;
        } else if (first != failure) {
            first.addSuppressed(failure);
        }
    }

    /** Returns whether at least one component failed. */
    public boolean hasFailure() {
        return first != null;
    }

    /** Returns the first failure, or {@code null} when every component completed normally. */
    public Throwable failure() {
        return first;
    }

    /** Throws the collected failures through the ordinary runtime-exception channel. */
    public void throwIfPresent(String operation) {
        if (first != null) {
            throw ComponentInvocationBoundary.exception(operation, first);
        }
    }

    /** Attaches every collected failure to an existing primary failure. */
    public void suppressInto(Throwable primaryFailure) {
        Objects.requireNonNull(primaryFailure, "primaryFailure");
        if (first != null && first != primaryFailure) {
            primaryFailure.addSuppressed(first);
        }
    }
}
