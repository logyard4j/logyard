package com.zsumz.logyard.core.runtime.retirement;

import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.diagnostics.EmergencyReporter;

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
