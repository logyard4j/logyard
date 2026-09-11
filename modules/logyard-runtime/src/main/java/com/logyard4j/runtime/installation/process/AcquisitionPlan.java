package com.logyard4j.runtime.installation.process;

import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.runtime.installation.RuntimeInstallation;

/** Immutable work reservation produced while holding the lifecycle lock. */
record AcquisitionPlan(
        Action action,
        RuntimeInstallation installation,
        LogyardRuntime borrowedRuntime,
        long generation,
        boolean installShutdownHook,
        RuntimeStartTransaction startTransaction,
        RuntimeRetirementPlan retirement) {

    enum Action {
        BORROW,
        SHARE,
        START,
        RECONFIGURE,
        CLOSE_STALE
    }

    static AcquisitionPlan borrowed(LogyardRuntime runtime) {
        return new AcquisitionPlan(Action.BORROW, null, runtime, 0, false, null, RuntimeRetirementPlan.none());
    }

    static AcquisitionPlan shared(RuntimeInstallation installation) {
        return new AcquisitionPlan(Action.SHARE, installation, null, 0, false, null, RuntimeRetirementPlan.none());
    }

    static AcquisitionPlan start(RuntimeStartTransaction transaction, boolean installShutdownHook) {
        return new AcquisitionPlan(
                Action.START,
                null,
                null,
                transaction.generation(),
                installShutdownHook,
                transaction,
                RuntimeRetirementPlan.none());
    }

    static AcquisitionPlan reconfigure(RuntimeInstallation installation, long generation) {
        return new AcquisitionPlan(
                Action.RECONFIGURE,
                installation,
                null,
                generation,
                false,
                null,
                RuntimeRetirementPlan.none());
    }

    static AcquisitionPlan closeStale(RuntimeRetirementPlan retirement) {
        RuntimeRetirementTransaction transaction = retirement.transaction();
        return new AcquisitionPlan(
                Action.CLOSE_STALE,
                transaction.installation(),
                null,
                transaction.generation(),
                false,
                null,
                retirement);
    }
}
