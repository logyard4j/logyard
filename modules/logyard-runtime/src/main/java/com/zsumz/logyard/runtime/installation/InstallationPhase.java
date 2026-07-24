package com.zsumz.logyard.runtime.installation;

/** Explicit process lifecycle phases; transitional phases reject recursive acquisition. */
enum InstallationPhase {
    EMPTY,
    STARTING,
    ACTIVE,
    RECONFIGURING,
    CLOSING
}
