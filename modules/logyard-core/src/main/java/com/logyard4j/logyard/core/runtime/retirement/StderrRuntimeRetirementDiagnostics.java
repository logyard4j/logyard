package com.logyard4j.logyard.core.runtime.retirement;

import com.logyard4j.logyard.core.diagnostics.EmergencyText;
import com.logyard4j.logyard.core.diagnostics.EmergencyReporter;

/** Bounded standard-error diagnostics for runtime retirement. */
final class StderrRuntimeRetirementDiagnostics implements RuntimeRetirementDiagnostics {
    @Override
    public void retirementFailed(Throwable failure) {
        EmergencyReporter.STDERR.report("Logyard output retirement failed: " + EmergencyText.failureSummary(failure, 4_096));
    }

    @Override
    public void shutdownDeadlineElapsed() {
        EmergencyReporter.STDERR.report("Logyard shutdown deadline elapsed before all outputs retired");
    }
}
