package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardRuntime;

/** Immutable work reservation produced while holding the lifecycle lock. */
record AcquisitionPlan(
        Action action,
        RuntimeInstallation installation,
        LogyardRuntime borrowedRuntime,
        long generation,
        boolean installShutdownHook) {

    enum Action {
        BORROW,
        SHARE,
        START,
        RECONFIGURE,
        CLOSE_STALE
    }

    static AcquisitionPlan borrowed(LogyardRuntime runtime) {
        return new AcquisitionPlan(Action.BORROW, null, runtime, 0, false);
    }

    static AcquisitionPlan shared(RuntimeInstallation installation) {
        return new AcquisitionPlan(Action.SHARE, installation, null, 0, false);
    }

    static AcquisitionPlan start(long generation, boolean installShutdownHook) {
        return new AcquisitionPlan(Action.START, null, null, generation, installShutdownHook);
    }

    static AcquisitionPlan reconfigure(RuntimeInstallation installation, long generation) {
        return new AcquisitionPlan(Action.RECONFIGURE, installation, null, generation, false);
    }

    static AcquisitionPlan closeStale(RuntimeInstallation installation, long generation) {
        return new AcquisitionPlan(Action.CLOSE_STALE, installation, null, generation, false);
    }
}
