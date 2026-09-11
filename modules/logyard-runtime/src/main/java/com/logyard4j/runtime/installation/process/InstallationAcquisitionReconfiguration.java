package com.logyard4j.runtime.installation.process;

import com.logyard4j.api.reload.ReloadResult;
import com.logyard4j.runtime.installation.ConfigurationInstallationRequest;
import com.logyard4j.runtime.installation.RuntimeInstallation;

import java.util.Objects;

/** Owns the process-manager handoff transaction around an already active runtime installation. */
final class InstallationAcquisitionReconfiguration {
    private final RuntimeInstallationState state;
    private final RuntimeInstallationRetirementCoordinator retirements;

    InstallationAcquisitionReconfiguration(
            RuntimeInstallationState state,
            RuntimeInstallationRetirementCoordinator retirements) {
        this.state = Objects.requireNonNull(state, "state");
        this.retirements = Objects.requireNonNull(retirements, "retirements");
    }

    RuntimeInstallation reconfigure(
            RuntimeOwner owner,
            ConfigurationInstallationRequest request,
            AcquisitionPlan plan) {
        try {
            ReloadResult result = Objects.requireNonNull(plan.installation().reconfigure(request), "runtime reconfiguration result");
            if (result == ReloadResult.REJECTED) {
                throw InstallationTransitionFailures.rejectedHandoff();
            }
            state.commitReconfiguration(owner, plan);
            return plan.installation();
        } catch (RuntimeException | Error failure) {
            retirements.retire(state.rollbackReconfiguration(owner, plan));
            throw failure;
        }
    }
}
