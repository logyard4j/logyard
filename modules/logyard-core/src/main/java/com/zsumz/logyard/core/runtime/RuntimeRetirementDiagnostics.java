package com.zsumz.logyard.core.runtime;

/** Strategy for reporting asynchronous runtime-retirement failures and shutdown timeouts. */
interface RuntimeRetirementDiagnostics {
    void retirementFailed(Throwable failure);

    void shutdownDeadlineElapsed();
}
