package com.logyard4j.runtime.installation;

import com.logyard4j.runtime.assembly.RuntimeAssembly;

/** Rollback cleanup for an uncommitted configuration handoff. */
final class RuntimeConfigurationCleanup {
    private RuntimeConfigurationCleanup() {
    }

    static void closeReplacement(
            ActiveRuntimeConfiguration replacement,
            RuntimeAssembly candidate,
            RuntimeAssembly current,
            Throwable failure) {
        if (replacement != null) {
            try {
                replacement.closeWatcher();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (candidate != null) {
            candidate.closeCandidateOutputs(current, failure);
        }
    }
}
