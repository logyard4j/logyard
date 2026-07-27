package com.zsumz.logyard.core.runtime.retirement;

import com.zsumz.logyard.core.diagnostics.EmergencyText;

/** Bounded standard-error diagnostics for runtime retirement. */
final class StderrRuntimeRetirementDiagnostics implements RuntimeRetirementDiagnostics {
    @Override
    public void retirementFailed(Throwable failure) {
        System.err.println("Logyard output retirement failed: " + EmergencyText.failureSummary(failure, 4_096));
    }

    @Override
    public void shutdownDeadlineElapsed() {
        System.err.println("Logyard shutdown deadline elapsed before all outputs retired");
    }
}
