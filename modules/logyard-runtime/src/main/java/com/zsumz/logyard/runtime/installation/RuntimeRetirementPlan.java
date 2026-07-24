package com.zsumz.logyard.runtime.installation;

/** Installation and generation selected atomically for retirement outside the lifecycle lock. */
record RuntimeRetirementPlan(RuntimeInstallation installation, long generation) {
    private static final RuntimeRetirementPlan NONE = new RuntimeRetirementPlan(null, 0L);

    static RuntimeRetirementPlan none() {
        return NONE;
    }

    static RuntimeRetirementPlan close(RuntimeInstallation installation, long generation) {
        return new RuntimeRetirementPlan(installation, generation);
    }

    boolean required() {
        return installation != null;
    }
}
