package com.logyard4j.core.runtime.retirement;

/** Strategy for reporting asynchronous runtime-retirement failures and shutdown timeouts. */
interface RuntimeRetirementDiagnostics {
    void retirementFailed(Throwable failure);

    void shutdownDeadlineElapsed();
}
