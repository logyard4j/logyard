package com.logyard4j.core.failure;

/** Reports one recoverable failure after it has crossed a component boundary. */
@FunctionalInterface
public interface ComponentFailureReporter {
    void report(String component, Throwable failure);
}
