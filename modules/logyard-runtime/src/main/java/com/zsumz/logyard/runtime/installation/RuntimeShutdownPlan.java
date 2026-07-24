package com.zsumz.logyard.runtime.installation;

/** Result of one atomic lifecycle-shutdown decision. */
record RuntimeShutdownPlan(
        boolean accepted,
        RuntimeRetirementPlan retirement,
        RuntimeStartTransaction cancelledStart) {

    static RuntimeShutdownPlan rejected() {
        return new RuntimeShutdownPlan(false, RuntimeRetirementPlan.none(), null);
    }

    static RuntimeShutdownPlan allow() {
        return new RuntimeShutdownPlan(true, RuntimeRetirementPlan.none(), null);
    }

    static RuntimeShutdownPlan cancel(RuntimeStartTransaction start) {
        return new RuntimeShutdownPlan(true, RuntimeRetirementPlan.none(), start);
    }

    static RuntimeShutdownPlan retire(RuntimeInstallation installation, long generation) {
        return new RuntimeShutdownPlan(true, RuntimeRetirementPlan.close(installation, generation), null);
    }
}
