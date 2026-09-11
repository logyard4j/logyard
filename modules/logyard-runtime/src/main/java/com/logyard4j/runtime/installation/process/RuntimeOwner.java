package com.logyard4j.runtime.installation.process;

/** Lifecycle participant holding a lease on the process-wide runtime. */
enum RuntimeOwner {
    APPLICATION(1),
    FRAMEWORK(2),
    ADAPTER(0);

    private final int configurationPriority;

    RuntimeOwner(int configurationPriority) {
        this.configurationPriority = configurationPriority;
    }

    boolean canReplace(RuntimeOwner currentAuthority) {
        return currentAuthority == null
                || configurationPriority > currentAuthority.configurationPriority
                || this == currentAuthority && this != ADAPTER;
    }
}
