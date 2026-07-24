package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardRuntime;

/** Immutable work reservation produced while holding the lifecycle lock. */
record AcquisitionPlan(
        Action action,
        RuntimeInstallation installation,
        LogyardRuntime borrowedRuntime,
        long generation,
        boolean installShutdownHook,
        RuntimeStartTransaction startTransaction) {

    enum Action {
        BORROW,
        SHARE,
        START,
        RECONFIGURE,
        CLOSE_STALE
    }

    static AcquisitionPlan borrowed(LogyardRuntime runtime) {
        return new AcquisitionPlan(Action.BORROW, null, runtime, 0, false, null);
    }

    static AcquisitionPlan shared(RuntimeInstallation installation) {
        return new AcquisitionPlan(Action.SHARE, installation, null, 0, false, null);
    }

    static AcquisitionPlan start(RuntimeStartTransaction transaction, boolean installShutdownHook) {
        return new AcquisitionPlan(Action.START, null, null, transaction.generation(), installShutdownHook, transaction);
    }

    static AcquisitionPlan reconfigure(RuntimeInstallation installation, long generation) {
        return new AcquisitionPlan(Action.RECONFIGURE, installation, null, generation, false, null);
    }

    static AcquisitionPlan closeStale(RuntimeInstallation installation, long generation) {
        return new AcquisitionPlan(Action.CLOSE_STALE, installation, null, generation, false, null);
    }
}
