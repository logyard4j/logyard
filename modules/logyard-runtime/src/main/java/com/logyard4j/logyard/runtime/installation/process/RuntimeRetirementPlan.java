package com.logyard4j.logyard.runtime.installation.process;

import com.logyard4j.logyard.runtime.installation.RuntimeInstallation;

/** Shared retirement transaction selected atomically for execution outside the lifecycle lock. */
record RuntimeRetirementPlan(RuntimeRetirementTransaction transaction) {
    private static final RuntimeRetirementPlan NONE = new RuntimeRetirementPlan(null);

    static RuntimeRetirementPlan none() {
        return NONE;
    }

    static RuntimeRetirementPlan close(RuntimeInstallation installation, long generation) {
        return close(new RuntimeRetirementTransaction(installation, generation));
    }

    static RuntimeRetirementPlan close(RuntimeRetirementTransaction transaction) {
        return new RuntimeRetirementPlan(transaction);
    }

    boolean required() {
        return transaction != null;
    }
}
