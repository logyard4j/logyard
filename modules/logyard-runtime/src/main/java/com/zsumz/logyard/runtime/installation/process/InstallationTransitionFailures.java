package com.zsumz.logyard.runtime.installation.process;

/** Central lifecycle-transition diagnostics shared by acquisition and handoff paths. */
final class InstallationTransitionFailures {
    private InstallationTransitionFailures() {
    }

    static IllegalStateException forPhase(InstallationPhase phase) {
        if (phase == InstallationPhase.TERMINATED) {
            return new IllegalStateException(
                    "Logyard runtime installation is terminated; process-shutdown acquisition is not allowed");
        }
        return new RuntimeTransitionInProgressException("Logyard runtime installation is " + phase.name().toLowerCase()
                + "; recursive acquisition is not allowed during lifecycle transitions");
    }

    static RuntimeTransitionInProgressException rejectedHandoff() {
        return new RuntimeTransitionInProgressException(
                "Logyard runtime rejected configuration handoff while another reload is active; retry acquisition");
    }
}
